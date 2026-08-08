package com.example.mateon.common.storage;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 버킷 총 사용량 상한을 앱에서 지킨다.
 *
 * <p>OCI Object Storage 는 <b>버킷 단위 쿼터를 제공하지 않는다</b>. 콤파트먼트 쿼터
 * (object-storage-bytes)는 콤파트먼트 전체에만 걸리고, 걸리는 순간 그 안의 모든 버킷이 함께
 * 막히는 데다 우리 앱은 초과 사실을 업로드가 실패한 뒤에야 안다. 그래서 "얼마나 찼는지"를
 * 우리가 세고, 넘길 요청을 <b>업로드 전에</b> 거절한다.
 *
 * <p>세는 방법은 두 겹이다.
 * <ul>
 *   <li><b>증분</b>: 업로드 직전에 예약(reserve), 삭제 후에 반납(release). 요청 경로에서
 *       버킷을 훑지 않으므로 업로드 한 번에 드는 추가 왕복이 없다.</li>
 *   <li><b>재실측</b>: 기동 직후와 {@code storage.usage-sync-cron} 주기마다 ListObjectsV2 로
 *       실제 합계를 다시 잰다. 같은 키 덮어쓰기, 콘솔에서의 직접 삭제, 롤백을 놓친 예외 등
 *       증분만으로는 생기는 어긋남을 여기서 원점으로 되돌린다.</li>
 * </ul>
 *
 * <p><b>단일 인스턴스 전제다</b> (SchedulingConfig 와 같은 제약). 카운터가 JVM 힙에 있으므로
 * N 대로 늘리면 각자 자기 한도까지 채워 실제 사용량이 최대 N 배가 된다. 스케일아웃 시점에는
 * 카운터를 Redis 나 DB 행 하나로 옮기고 예약을 원자 증가로 바꿔야 한다. 그때까지의 완충은
 * {@code storage.max-bytes} 를 실제 여유보다 낮게 잡는 것으로 대신한다.
 */
@Slf4j
@Component
public class BucketCapacityGuard {

    /** 목록 조회 한 번에 받는 객체 수. S3 호환 API 의 상한값이라 왕복 횟수가 최소가 된다. */
    private static final int PAGE_SIZE = 1000;

    /**
     * 재실측이 훑는 페이지 상한(= 100만 객체). 엔드포인트가 같은 토큰을 계속 돌려주는 등의
     * 이상 상황에서 스케줄러 스레드가 영영 갇히지 않게 하는 안전장치다.
     */
    private static final int MAX_PAGES = 1000;

    private final S3Client ociS3Client;
    private final ObjectStorageProperties properties;

    /** 상한(바이트). 0 이면 이 가드 전체가 비활성이다. */
    private final long maxBytes;

    /** 버킷에 들어 있다고 보는 총 바이트. 아직 put 이 안 끝난 예약분도 포함한다. */
    private final AtomicLong usedBytes = new AtomicLong();

    /** 예약했지만 아직 put 이 끝나지 않은 바이트. 재실측이 진행 중인 업로드를 지우지 않게 한다. */
    private final AtomicLong inFlightBytes = new AtomicLong();

    /** 한 번이라도 실측에 성공했는지. 실패 상태에서는 업로드를 막는다(fail-closed). */
    private final AtomicBoolean measured = new AtomicBoolean(false);

    public BucketCapacityGuard(S3Client ociS3Client, ObjectStorageProperties properties) {
        this.ociS3Client = ociS3Client;
        this.properties = properties;
        this.maxBytes = properties.getMaxBytes() == null ? 0 : properties.getMaxBytes().toBytes();
    }

    /** 상한이 설정돼 있는지. 꺼져 있으면 이 클래스의 모든 메서드가 아무 일도 하지 않는다. */
    public boolean isEnabled() {
        return maxBytes > 0;
    }

    // --- 실측 ---------------------------------------------------------------

    /**
     * 기동 후 첫 실측. {@code @PostConstruct} 가 아닌 이유는 <b>부팅을 저장소 가용성에 묶지 않기
     * 위해서</b>다 — OCI 가 잠깐 흔들릴 때 배포가 통째로 실패하면 손해가 더 크다. 대신 실측 전
     * 업로드는 {@link #reserve} 가 막으므로 한도가 새는 구간은 없다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (!isEnabled()) {
            // 조용히 꺼져 있으면 "한도를 걸어 뒀다"고 착각하기 쉽다. 부팅 로그에 한 줄 남긴다.
            log.warn("버킷 용량 상한이 꺼져 있습니다 — 무제한으로 업로드됩니다. "
                    + "제한하려면 .env 에 OCI_BUCKET_MAX_BYTES=18GB 처럼 지정하세요.");
            return;
        }
        // 실측 결과와 별개로 "지금 어떤 한도로 떠 있는지"를 먼저 남긴다. 아래 sync() 로그는
        // 저장소가 응답해야 나오므로, 실측이 실패하면 설정값조차 확인할 수 없게 된다.
        log.info("버킷 용량 상한: bucket={}, max={} ({} bytes)",
                properties.getBucket(), humanReadable(maxBytes), maxBytes);
        sync();
    }

    /** 증분 카운터가 실제 버킷과 어긋난 것을 주기적으로 원점 복귀시킨다. */
    @Scheduled(cron = "${storage.usage-sync-cron}")
    public void syncPeriodically() {
        sync();
    }

    private void sync() {
        if (!isEnabled()) {
            return;
        }
        try {
            long listed = measureBucketBytes();
            // 스캔 도중 예약된 업로드는 목록에 잡혔을 수도, 아닐 수도 있다. 겹쳐 세는 쪽을 택한다 —
            // 한도 앞에서 잠깐 보수적으로 구는 건 안전하지만, 빠뜨리면 한도를 넘겨 버린다.
            // 겹쳐 센 값은 다음 재실측에서 정정된다.
            usedBytes.set(listed + inFlightBytes.get());
            measured.set(true);
            log.info("버킷 사용량 재실측: bucket={}, used={} / max={} ({}%), 남은 용량={}",
                    properties.getBucket(), humanReadable(listed), humanReadable(maxBytes),
                    listed * 100 / maxBytes, humanReadable(Math.max(0, maxBytes - listed)));
        } catch (SdkException e) {
            // measured 는 건드리지 않는다. 이전에 성공한 값이 있으면 그걸로 계속 판단하는 게,
            // 저장소가 잠깐 흔들렸다고 업로드를 통째로 막는 것보다 낫다.
            log.error("버킷 사용량 재실측 실패 — 직전 값으로 계속 판단합니다: bucket={}", properties.getBucket(), e);
        }
    }

    /**
     * 로그용 크기 표기. 바이트 원값만 찍으면 GB 단위에서 자릿수를 세야 읽을 수 있다.
     *
     * <p>1024 단위(GiB)로 나눈다 — {@code storage.max-bytes} 를 파싱하는 Spring 의 DataSize 가
     * 같은 기준이라, 설정에 적은 "2GB" 가 로그에도 "2.00GB" 로 그대로 되비쳐야 한다.
     *
     * <p>Locale.ROOT 를 박는 이유: 기본 로캘이 소수점을 쉼표로 찍는 환경에서 "2,00GB" 가 되면
     * 로그를 기계로 긁을 때 걸린다.
     */
    private static String humanReadable(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.2fGB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format(Locale.ROOT, "%.1fMB", bytes / (double) (1L << 20));
        }
        if (bytes >= 1L << 10) {
            return String.format(Locale.ROOT, "%.1fKB", bytes / (double) (1L << 10));
        }
        return bytes + "B";
    }

    /** 버킷 전체를 페이지로 훑어 크기를 합산한다. 요청 경로에서는 절대 부르지 않는다. */
    private long measureBucketBytes() {
        long total = 0;
        String continuationToken = null;
        int pages = 0;

        do {
            ListObjectsV2Response response = ociS3Client.listObjectsV2(
                    ListObjectsV2Request.builder()
                            .bucket(properties.getBucket())
                            .continuationToken(continuationToken)
                            .maxKeys(PAGE_SIZE)
                            .build());
            for (S3Object object : response.contents()) {
                total += object.size();
            }
            continuationToken = response.nextContinuationToken();
        } while (continuationToken != null && ++pages < MAX_PAGES);

        if (continuationToken != null) {
            // 덜 센 값을 그대로 쓰면 한도를 넘겨 받아들이게 된다. 실측 실패로 취급한다.
            throw SdkException.builder()
                    .message("버킷 객체가 " + (long) MAX_PAGES * PAGE_SIZE + "개를 넘어 사용량을 다 세지 못했습니다")
                    .build();
        }
        return total;
    }

    // --- 증분 -------------------------------------------------------------

    /**
     * 업로드할 만큼의 자리를 미리 잡는다. 성공하면 반드시 {@link #commit} 또는 {@link #rollback}
     * 중 하나로 닫아야 한다 — 안 닫으면 그 바이트가 다음 재실측까지 점유된 채 남는다.
     *
     * <p>CAS 반복인 이유: 동시에 들어온 업로드 둘이 각자 "아직 여유 있음"을 읽고 둘 다 통과하는
     * 것을 막는다. 검사와 증가가 한 연산이어야 한다.
     *
     * @throws MateonException STORAGE_QUOTA_EXCEEDED (507) — 한도 초과 또는 사용량 미실측
     */
    public void reserve(long bytes) {
        if (!isEnabled()) {
            return;
        }
        if (!measured.get()) {
            // 실측 전에는 얼마나 찼는지 모른다. 모르는 채로 올리면 한도가 있으나 마나다.
            // 계속 이 로그가 찍힌다면 Customer Secret Key 에 버킷 읽기(목록) 권한이 없는 것이다.
            log.error("버킷 사용량을 아직 실측하지 못해 업로드를 막습니다: bucket={}", properties.getBucket());
            throw new MateonException(ErrorCode.STORAGE_QUOTA_EXCEEDED);
        }

        while (true) {
            long current = usedBytes.get();
            long next = current + bytes;
            if (next > maxBytes) {
                log.warn("버킷 용량 한도 초과로 업로드를 거절합니다: used={}B, request={}B, max={}B",
                        current, bytes, maxBytes);
                throw new MateonException(ErrorCode.STORAGE_QUOTA_EXCEEDED);
            }
            if (usedBytes.compareAndSet(current, next)) {
                inFlightBytes.addAndGet(bytes);
                return;
            }
        }
    }

    /** 업로드가 성공했다. 예약이 실제 사용량으로 확정된다. */
    public void commit(long bytes) {
        if (isEnabled()) {
            inFlightBytes.addAndGet(-bytes);
        }
    }

    /** 업로드가 실패했다. 잡아 둔 자리를 그대로 되돌린다. */
    public void rollback(long bytes) {
        if (isEnabled()) {
            inFlightBytes.addAndGet(-bytes);
            release(bytes);
        }
    }

    /** 객체가 지워져 그만큼 자리가 났다. */
    public void release(long bytes) {
        if (isEnabled() && bytes > 0) {
            // 음수로 내려가지 않게 막는다. 재실측 직후 그 이전에 시작된 삭제가 반납하면
            // 이미 빠진 몫을 한 번 더 빼게 되는데, 그 오차가 카운터에 눌러앉지 않도록 한다.
            usedBytes.updateAndGet(current -> Math.max(0, current - bytes));
        }
    }

    /**
     * 삭제 전에 반납할 크기를 알아낸다(HEAD 한 번). 크기를 모르면 0 을 돌려주고, 그만큼의
     * 어긋남은 다음 재실측이 정리한다 — 삭제 자체를 막을 이유는 없다.
     */
    public long sizeOf(String key) {
        if (!isEnabled()) {
            return 0;
        }
        try {
            return ociS3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build()).contentLength();
        } catch (NoSuchKeyException e) {
            // 이미 없는 객체다. 반납할 자리도 없다.
            return 0;
        } catch (SdkException e) {
            log.warn("삭제 대상 크기를 확인하지 못했습니다 — 사용량은 다음 재실측에서 정정됩니다: key={}", key, e);
            return 0;
        }
    }

    // --- 사전 확인 -----------------------------------------------------------

    /**
     * 자리를 잡지 않고 여유만 본다. 실제 업로드가 <b>나중에/다른 스레드에서</b> 일어나는 곳
     * (프로필 이미지의 비동기 워커, 활동 추출의 AI 호출 앞)에서 쓴다.
     *
     * <p>여기서 통과해도 {@link #reserve} 가 거절할 수 있다(그 사이 다른 업로드가 채웠을 때).
     * 이 검사의 목적은 한도 보장이 아니라, <b>확실히 실패할 요청에 비용을 들이기 전에</b>
     * 사용자에게 동기 응답으로 알려 주는 것이다.
     *
     * @throws MateonException STORAGE_QUOTA_EXCEEDED (507)
     */
    public void checkRoomFor(long bytes) {
        if (!isEnabled()) {
            return;
        }
        if (!measured.get() || usedBytes.get() + bytes > maxBytes) {
            log.warn("버킷 여유가 없어 요청을 접수 단계에서 거절합니다: used={}B, request={}B, max={}B",
                    usedBytes.get(), bytes, maxBytes);
            throw new MateonException(ErrorCode.STORAGE_QUOTA_EXCEEDED);
        }
    }
}

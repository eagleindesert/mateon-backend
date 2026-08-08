package com.example.mateon.common.storage;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 버킷 총량 한도가 실제로 지켜지는지 고정한다.
 *
 * <p>OCI 가 버킷 단위 쿼터를 안 주기 때문에 이 클래스가 유일한 방어선이다. 여기가 틀리면 한도를
 * 넘겨 저장하거나(요금/무료한도 초과), 반대로 자리가 남았는데도 업로드를 막게 된다.
 *
 * <p>동시 업로드 케이스를 넣은 이유: "읽고 → 검사하고 → 더한다"로 짜면 두 요청이 같은 여유를
 * 각자 보고 둘 다 통과한다. 예약이 원자적이어야 한다는 게 이 가드의 핵심이라 테스트로 못박는다.
 */
class BucketCapacityGuardTest {

    private static final String BUCKET = "mateon";

    private S3Client s3Client;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
    }

    /** @param maxBytes 0 이면 가드가 꺼진 상태 */
    private BucketCapacityGuard guard(long maxBytes) {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setNamespace("ns");
        properties.setRegion("ap-chuncheon-1");
        properties.setBucket(BUCKET);
        properties.setMaxBytes(DataSize.ofBytes(maxBytes));
        return new BucketCapacityGuard(s3Client, properties);
    }

    /** 버킷에 주어진 크기의 객체들이 들어 있는 것으로 목록 조회를 응답한다(한 페이지). */
    private void bucketContains(long... sizes) {
        ListObjectsV2Response.Builder response = ListObjectsV2Response.builder();
        S3Object[] objects = new S3Object[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            objects[i] = S3Object.builder().key("object-" + i).size(sizes[i]).build();
        }
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(response.contents(objects).build());
    }

    /** 실측을 마친 가드. 실제 기동 순서(ApplicationReadyEvent → sync)와 같다. */
    private BucketCapacityGuard syncedGuard(long maxBytes, long... existingSizes) {
        bucketContains(existingSizes);
        BucketCapacityGuard guard = guard(maxBytes);
        guard.syncOnStartup();
        return guard;
    }

    @Test
    @DisplayName("남은 용량 안이면 예약이 통과한다")
    void reservesWithinLimit() {
        BucketCapacityGuard guard = syncedGuard(100, 30, 20);

        assertThatCode(() -> guard.reserve(50)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("남은 용량을 넘으면 STORAGE_QUOTA_EXCEEDED 로 거절한다")
    void rejectsOverLimit() {
        BucketCapacityGuard guard = syncedGuard(100, 30, 20);

        assertThatThrownBy(() -> guard.reserve(51))
                .isInstanceOf(MateonException.class)
                .extracting(e -> ((MateonException) e).getErrorCode())
                .isEqualTo(ErrorCode.STORAGE_QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("예약은 누적된다 — 각각은 여유 안이어도 합이 넘으면 거절한다")
    void accumulatesReservations() {
        BucketCapacityGuard guard = syncedGuard(100);

        guard.reserve(60);
        guard.commit(60);

        assertThatThrownBy(() -> guard.reserve(41)).isInstanceOf(MateonException.class);
    }

    @Test
    @DisplayName("업로드가 실패하면 잡아 둔 자리를 되돌린다")
    void rollbackFreesReservation() {
        BucketCapacityGuard guard = syncedGuard(100);

        guard.reserve(100);
        guard.rollback(100);

        // 되돌리지 않으면 실패한 업로드 한 번에 버킷이 영영 막힌 것처럼 보인다.
        assertThatCode(() -> guard.reserve(100)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("객체를 지우면 그만큼 다시 올릴 수 있다")
    void releaseFreesSpace() {
        BucketCapacityGuard guard = syncedGuard(100, 100);

        assertThatThrownBy(() -> guard.reserve(10)).isInstanceOf(MateonException.class);

        guard.release(40);

        assertThatCode(() -> guard.reserve(40)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("동시에 들어온 업로드 중 한도를 채우는 만큼만 통과시킨다")
    void reservationIsAtomicUnderConcurrency() throws Exception {
        // 10칸짜리 버킷에 1칸짜리 업로드 20개가 동시에 들어온다 — 정확히 10개만 통과해야 한다.
        BucketCapacityGuard guard = syncedGuard(10);

        int attempts = 20;
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);

        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        guard.reserve(1);
                        accepted.incrementAndGet();
                    } catch (MateonException expected) {
                        // 한도 초과 거절. 세지 않는다.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("사용량을 아직 실측하지 못했으면 업로드를 막는다 (fail-closed)")
    void blocksUploadBeforeFirstMeasurement() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenThrow(SdkException.builder().message("boom").build());
        BucketCapacityGuard guard = guard(100);
        guard.syncOnStartup();

        // 얼마나 찼는지 모르는 채로 받아 주면 한도가 있으나 마나다.
        assertThatThrownBy(() -> guard.reserve(1))
                .isInstanceOf(MateonException.class)
                .extracting(e -> ((MateonException) e).getErrorCode())
                .isEqualTo(ErrorCode.STORAGE_QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("재실측이 실패해도 직전에 성공한 값으로 계속 판단한다")
    void keepsLastKnownUsageWhenResyncFails() {
        BucketCapacityGuard guard = syncedGuard(100, 30);

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenThrow(SdkException.builder().message("boom").build());
        guard.syncPeriodically();

        // 저장소가 잠깐 흔들렸다고 업로드를 통째로 막지 않는다.
        assertThatCode(() -> guard.reserve(70)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("재실측은 증분 카운터의 어긋남을 실제 버킷 값으로 되돌린다")
    void resyncCorrectsDrift() {
        BucketCapacityGuard guard = syncedGuard(100, 90);

        assertThatThrownBy(() -> guard.reserve(20)).isInstanceOf(MateonException.class);

        // 콘솔에서 직접 지우는 등 우리가 모르는 사이 버킷이 비었다.
        bucketContains(10);
        guard.syncPeriodically();

        assertThatCode(() -> guard.reserve(20)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("재실측 중인 예약분은 사라지지 않는다")
    void resyncKeepsInFlightReservations() {
        BucketCapacityGuard guard = syncedGuard(100, 50);
        guard.reserve(50);

        // 예약만 하고 아직 put 이 끝나지 않은 상태에서 재실측이 돈다 — 목록에는 안 잡힌다.
        bucketContains(50);
        guard.syncPeriodically();

        // 진행 중인 50 을 빠뜨렸다면 여기서 통과해 버려 한도를 넘긴다.
        assertThatThrownBy(() -> guard.reserve(1)).isInstanceOf(MateonException.class);
    }

    @Test
    @DisplayName("한도가 꺼져 있으면 저장소를 부르지도, 거절하지도 않는다")
    void disabledGuardIsNoOp() {
        BucketCapacityGuard guard = guard(0);
        guard.syncOnStartup();

        assertThat(guard.isEnabled()).isFalse();
        assertThatCode(() -> guard.reserve(Long.MAX_VALUE)).doesNotThrowAnyException();
        assertThatCode(() -> guard.checkRoomFor(Long.MAX_VALUE)).doesNotThrowAnyException();
        assertThat(guard.sizeOf("any/key.png")).isZero();
        verify(s3Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    @DisplayName("삭제할 객체의 크기를 HEAD 로 알아낸다")
    void readsSizeOfObjectToDelete() {
        BucketCapacityGuard guard = syncedGuard(100, 40);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(40L).build());

        assertThat(guard.sizeOf("profile-images/2026/07/x.png")).isEqualTo(40);
    }

    @Test
    @DisplayName("크기를 못 알아내면 0 을 돌려주고 삭제를 막지 않는다")
    void treatsUnknownSizeAsZero() {
        BucketCapacityGuard guard = syncedGuard(100, 40);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("gone").build());

        // 반납을 못 한 만큼은 다음 재실측이 정리한다. 삭제 자체를 막을 이유는 없다.
        assertThat(guard.sizeOf("profile-images/2026/07/gone.png")).isZero();
    }

    @Test
    @DisplayName("여유 확인(checkRoomFor)은 자리를 잡지 않는다")
    void checkRoomForDoesNotReserve() {
        BucketCapacityGuard guard = syncedGuard(100);

        guard.checkRoomFor(100);
        guard.checkRoomFor(100);

        // 확인만으로 자리가 줄었다면 여기서 거절됐을 것이다.
        assertThatCode(() -> guard.reserve(100)).doesNotThrowAnyException();
    }
}

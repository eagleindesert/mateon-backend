package com.example.mateon.common.storage;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 객체 저장소 업로드. 지금은 공모전 포스터 이미지만 쓰지만 프로필 이미지 등도 같은 경로를
 * 쓸 수 있도록 도메인 무관한 common 아래 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObjectStorageService {

    private final S3Client ociS3Client;
    private final ObjectStorageProperties properties;
    private final BucketCapacityGuard capacityGuard;

    /**
     * 바이트 배열을 버킷에 올리고 공개 조회 URL 을 돌려준다.
     *
     * <p>버킷 총량 한도 검사({@link BucketCapacityGuard})가 여기 붙어 있는 이유: 업로드가 이 한
     * 곳을 지나므로, 새 업로드 기능을 붙이는 사람이 한도 검사를 잊을 수 없다.
     *
     * @param key 버킷 내 객체 키 (예: contest-images/2026/07/{uuid}.jpg)
     * @throws MateonException STORAGE_QUOTA_EXCEEDED (507) — 버킷 총량 한도 초과,
     *                         IMAGE_UPLOAD_FAILED (502) — 저장소 장애/자격증명 오류
     */
    public String upload(String key, byte[] bytes, String contentType) {
        capacityGuard.reserve(bytes.length);

        boolean stored = false;
        try {
            ociS3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(bytes));
            stored = true;
        } catch (SdkException e) {
            // 자격증명 오류(403)와 네트워크 장애가 같은 예외 계열로 오므로 메시지를 남겨 둔다.
            log.error("OCI Object Storage 업로드 실패: bucket={}, key={}", properties.getBucket(), key, e);
            throw new MateonException(ErrorCode.IMAGE_UPLOAD_FAILED);
        } finally {
            // finally 인 이유: SdkException 외의 무엇이 튀어도 예약이 남지 않아야 한다.
            // 예약이 새면 그 바이트는 다음 재실측까지 아무도 못 쓰는 자리로 묶인다.
            if (stored) {
                capacityGuard.commit(bytes.length);
            } else {
                capacityGuard.rollback(bytes.length);
            }
        }
        return publicUrl(key);
    }

    /**
     * {@link #upload} 가 돌려준 공개 URL 의 객체를 지운다. 객체 키는 따로 보관하지 않고 URL 에서
     * 되짚는다 — URL 을 조립하는 곳이 여기라 역변환도 여기 있어야 형식 지식이 흩어지지 않는다.
     *
     * <p>우리 버킷 URL 이 아니면 조용히 넘어간다(경고 로그). 지울 대상이 우리 버킷에 없다는
     * 뜻이므로 실패로 볼 일이 아니다. 저장되는 URL 은 전부 우리가 만든 값이라 정상 경로에서는
     * 걸리지 않는, 설정(버킷/리전) 변경 후를 위한 방어 분기다.
     *
     * @throws MateonException IMAGE_DELETE_FAILED (502) — 저장소 장애/자격증명 오류.
     *                         호출부(ProfileImageWorker)가 잡아서 처리한다.
     */
    public void delete(String publicUrl) {
        String prefix = publicUrlPrefix();
        if (publicUrl == null || !publicUrl.startsWith(prefix)) {
            log.warn("우리 버킷의 URL 이 아니어서 삭제를 건너뜁니다: url={}, prefix={}", publicUrl, prefix);
            return;
        }

        String key = decodeKey(publicUrl.substring(prefix.length()));

        // 크기는 지우기 전에 물어봐야 한다(지운 뒤엔 알 길이 없다). 한도가 꺼져 있으면 0 을
        // 돌려주므로 이 왕복 자체가 생기지 않는다.
        long freedBytes = capacityGuard.sizeOf(key);
        try {
            ociS3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .build());
        } catch (SdkException e) {
            log.error("OCI Object Storage 삭제 실패: bucket={}, key={}", properties.getBucket(), key, e);
            throw new MateonException(ErrorCode.IMAGE_DELETE_FAILED);
        }
        // 삭제가 확정된 뒤에만 반납한다. 실패한 삭제를 반납하면 없는 자리를 내주게 된다.
        capacityGuard.release(freedBytes);
    }

    /**
     * 공개 URL 은 업로드에 쓴 compat 엔드포인트가 아니라 OCI 네이티브 형식으로 만든다.
     * compat 도메인은 서명된 S3 요청을 받는 곳이라 브라우저에서 그냥 열리지 않는다.
     * (버킷 가시성이 public 이어야 이 URL 이 인증 없이 열린다)
     */
    private String publicUrl(String key) {
        return publicUrlPrefix() + encodeKey(key);
    }

    /** 공개 URL 에서 키 앞까지의 고정 구간. {@link #delete} 가 키를 잘라내는 기준이다. */
    private String publicUrlPrefix() {
        return "https://objectstorage.%s.oraclecloud.com/n/%s/b/%s/o/".formatted(
                properties.getRegion(), properties.getNamespace(), properties.getBucket());
    }

    /** 키의 각 세그먼트만 인코딩한다 — '/' 는 경로 구분자로 살아 있어야 한다. */
    private String encodeKey(String key) {
        return Arrays.stream(key.split("/", -1))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    /**
     * {@link #encodeKey} 의 역함수. 세그먼트별로 디코딩한다 — 키 안에 있던 '/' 는 인코딩 때
     * 살려 뒀으므로 여기서도 구분자로 그대로 둔다.
     *
     * <p>URLDecoder 는 '+' 를 공백으로 되돌리는데, encodeKey 가 '+' 를 %20 으로 바꿔 두므로
     * 인코딩을 거친 값에서는 문제가 없다.
     */
    private String decodeKey(String encodedKey) {
        return Arrays.stream(encodedKey.split("/", -1))
                .map(segment -> URLDecoder.decode(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }
}

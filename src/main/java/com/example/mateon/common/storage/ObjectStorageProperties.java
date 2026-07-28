package com.example.mateon.common.storage;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OCI Object Storage 접속 정보. S3 호환 API 를 쓰므로 필요한 건 access/secret key 뿐이고,
 * OCI 네이티브 SDK 처럼 ~/.oci/config 와 개인키 PEM 을 배포할 필요가 없다.
 *
 * <p>namespace/region 은 접속(엔드포인트)에도, 반환할 공개 URL 조립에도 쓰인다 —
 * 두 URL 의 형태가 다르다는 점은 {@link ObjectStorageService} 주석 참고.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "storage")
public class ObjectStorageProperties {

    /** 테넌시 오브젝트 스토리지 네임스페이스 (OCI 콘솔 > 버킷 상세에 표시된다). */
    private String namespace;

    /** 버킷이 있는 리전 식별자 (예: ap-chuncheon-1). */
    private String region;

    /** 업로드 대상 버킷. 공개 조회 URL 을 그대로 내려주므로 가시성이 public 이어야 한다. */
    private String bucket;

    /** Customer Secret Key 의 Access Key (OCI 콘솔 > 사용자 > Customer Secret Keys). */
    private String accessKey;

    /** Customer Secret Key 의 Secret Key. 생성 직후 한 번만 보여주므로 .env 에 보관한다. */
    private String secretKey;

    /**
     * 설정 누락을 부팅 시점에 잡는다. 이유는 AiServerProperties.validateInternalSecret 과 같다 —
     * @ConfigurationProperties 바인딩은 해결 못 한 플레이스홀더를 예외 없이 원문 그대로 넣기 때문에,
     * 프로퍼티에 기본값을 안 주는 것만으로는 부팅이 실패하지 않는다. 그대로 두면 첫 업로드 시점에야
     * "${OCI_BUCKET}" 이라는 버킷을 찾다가 404 로 터진다.
     */
    @PostConstruct
    void validate() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("OCI_NAMESPACE", namespace);
        required.put("OCI_REGION", region);
        required.put("OCI_BUCKET", bucket);
        required.put("OCI_S3_ACCESS_KEY", accessKey);
        required.put("OCI_S3_SECRET_KEY", secretKey);

        String missing = required.entrySet().stream()
                .filter(e -> !StringUtils.hasText(e.getValue()) || e.getValue().startsWith("${"))
                .map(Map.Entry::getKey)
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);

        if (missing != null) {
            throw new IllegalStateException(
                    "OCI Object Storage 설정이 비어 있습니다. .env 에 다음 값을 추가하세요: " + missing
                    + " (공모전 이미지 업로드에 사용됩니다)");
        }
    }
}

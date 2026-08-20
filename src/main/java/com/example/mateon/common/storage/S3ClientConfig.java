package com.example.mateon.common.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
@RequiredArgsConstructor
public class S3ClientConfig {

    private final ObjectStorageProperties properties;

    /**
     * OCI Object Storage 를 S3 호환 API 로 다루는 클라이언트.
     *
     * <p>
     * AWS 가 아닌 스토리지를 붙이는 것이라 기본값에서 세 가지를 바꿔야 한다.
     * <ul>
     * <li>엔드포인트: 네임스페이스별 compat 도메인으로 강제 (region 만으로는 AWS 로 간다)</li>
     * <li>path-style: OCI 는 버킷을 호스트명에 넣는 virtual-host 스타일을 지원하지 않는다</li>
     * <li>체크섬: AWS SDK 2.30 부터 요청에 CRC32 체크섬과 aws-chunked 인코딩을 기본으로
     * 붙이는데, OCI 는 이 조합을 거부해 업로드가 통째로 실패한다. WHEN_REQUIRED 로
     * 내려 "꼭 필요한 경우"에만 붙이게 한다.</li>
     * </ul>
     */
    @Bean
    public S3Client ociS3Client() {
        String endpoint = "https://%s.compat.objectstorage.%s.oraclecloud.com"
          .formatted(properties.getNamespace(), properties.getRegion());

        return S3Client.builder()
          .endpointOverride(URI.create(endpoint))
          .region(Region.of(properties.getRegion()))
          .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
          .forcePathStyle(true)
          .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
          .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
          .build();
    }
}

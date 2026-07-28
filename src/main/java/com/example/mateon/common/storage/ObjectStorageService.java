package com.example.mateon.common.storage;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

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

    /**
     * 바이트 배열을 버킷에 올리고 공개 조회 URL 을 돌려준다.
     *
     * @param key 버킷 내 객체 키 (예: contest-images/2026/07/{uuid}.jpg)
     * @throws MateonException IMAGE_UPLOAD_FAILED (502) — 저장소 장애/자격증명 오류
     */
    public String upload(String key, byte[] bytes, String contentType) {
        try {
            ociS3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            // 자격증명 오류(403)와 네트워크 장애가 같은 예외 계열로 오므로 메시지를 남겨 둔다.
            log.error("OCI Object Storage 업로드 실패: bucket={}, key={}", properties.getBucket(), key, e);
            throw new MateonException(ErrorCode.IMAGE_UPLOAD_FAILED);
        }
        return publicUrl(key);
    }

    /**
     * 공개 URL 은 업로드에 쓴 compat 엔드포인트가 아니라 OCI 네이티브 형식으로 만든다.
     * compat 도메인은 서명된 S3 요청을 받는 곳이라 브라우저에서 그냥 열리지 않는다.
     * (버킷 가시성이 public 이어야 이 URL 이 인증 없이 열린다)
     */
    private String publicUrl(String key) {
        return "https://objectstorage.%s.oraclecloud.com/n/%s/b/%s/o/%s".formatted(
                properties.getRegion(), properties.getNamespace(), properties.getBucket(), encodeKey(key));
    }

    /** 키의 각 세그먼트만 인코딩한다 — '/' 는 경로 구분자로 살아 있어야 한다. */
    private String encodeKey(String key) {
        return Arrays.stream(key.split("/", -1))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }
}

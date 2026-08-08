package com.example.mateon.common.storage;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 삭제가 공개 URL 하나만 가지고 객체를 찾아낼 수 있는지 고정한다.
 *
 * <p>객체 키를 DB 에 따로 저장하지 않기로 했으므로(users.profile_image_url 만 둔다) URL→키
 * 역변환이 어긋나면 이전 사진을 지우는 길이 아예 사라진다. 그래서 인코딩이 개입하는 키
 * (공백·한글)로 왕복을 확인한다.
 */
class ObjectStorageServiceTest {

    private S3Client s3Client;
    private ObjectStorageService service;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);

        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setNamespace("ns");
        properties.setRegion("ap-chuncheon-1");
        properties.setBucket("mateon");
        properties.setAccessKey("access");
        properties.setSecretKey("secret");

        // max-bytes 를 안 주면 용량 가드는 꺼진 상태다 — 아래 테스트들은 가드가 껴 있어도
        // 업로드·삭제 동작이 이전 그대로임을 함께 확인한다(HEAD 왕복도 생기지 않는다).
        service = new ObjectStorageService(s3Client, properties, new BucketCapacityGuard(s3Client, properties));
    }

    private String uploadAndGetUrl(String key) {
        return service.upload(key, new byte[]{1, 2, 3}, "image/png");
    }

    @Test
    @DisplayName("업로드가 돌려준 URL 로 같은 키의 객체를 지운다")
    void deletesObjectBehindPublicUrl() {
        String key = "profile-images/2026/07/2f1c9a10-0000-4000-8000-000000000001.png";

        service.delete(uploadAndGetUrl(key));

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("mateon");
        assertThat(request.getValue().key()).isEqualTo(key);
    }

    @Test
    @DisplayName("인코딩이 개입하는 키(공백·한글)도 원래 값으로 되돌린다")
    void restoresEncodedKey() {
        String key = "profile-images/2026/07/내 프로필 사진.png";

        service.delete(uploadAndGetUrl(key));

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(request.capture());
        assertThat(request.getValue().key()).isEqualTo(key);
    }

    @Test
    @DisplayName("우리 버킷 URL 이 아니면 저장소를 부르지 않고 넘어간다")
    void skipsForeignUrl() {
        service.delete("https://cdn.example.com/somebody-elses/photo.png");
        service.delete(null);

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("저장소 삭제 실패는 IMAGE_DELETE_FAILED 로 올린다 (업로드 실패와 구분한다)")
    void translatesSdkFailure() {
        doThrow(SdkException.builder().message("boom").build())
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        String url = uploadAndGetUrl("profile-images/2026/07/x.png");

        assertThatThrownBy(() -> service.delete(url))
                .isInstanceOf(MateonException.class)
                .extracting(e -> ((MateonException) e).getErrorCode())
                .isEqualTo(ErrorCode.IMAGE_DELETE_FAILED);
    }

    @Test
    @DisplayName("업로드 실패는 IMAGE_UPLOAD_FAILED 로 올린다")
    void translatesUploadFailure() {
        doThrow(SdkException.builder().message("boom").build())
                .when(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        assertThatThrownBy(() -> uploadAndGetUrl("profile-images/2026/07/x.png"))
                .isInstanceOf(MateonException.class)
                .extracting(e -> ((MateonException) e).getErrorCode())
                .isEqualTo(ErrorCode.IMAGE_UPLOAD_FAILED);
    }

    @Test
    @DisplayName("용량 가드가 꺼져 있으면 삭제 전에 크기를 묻지 않는다")
    void skipsHeadWhenCapacityGuardIsOff() {
        service.delete(uploadAndGetUrl("profile-images/2026/07/x.png"));

        // HEAD 는 한도를 셀 때만 필요한 왕복이다. 한도를 안 쓰는 배포에 비용을 얹지 않는다.
        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    @DisplayName("한도가 켜져 있으면 남은 용량을 넘는 업로드를 저장소에 보내기 전에 거절한다")
    void rejectsUploadOverQuotaBeforeCallingStorage() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setNamespace("ns");
        properties.setRegion("ap-chuncheon-1");
        properties.setBucket("mateon");
        properties.setMaxBytes(DataSize.ofBytes(10));

        BucketCapacityGuard guard = new BucketCapacityGuard(s3Client, properties);
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("old.png").size(8L).build())
                        .build());
        guard.syncOnStartup();

        ObjectStorageService guarded = new ObjectStorageService(s3Client, properties, guard);

        assertThatThrownBy(() -> guarded.upload("profile-images/2026/07/x.png", new byte[5], "image/png"))
                .isInstanceOf(MateonException.class)
                .extracting(e -> ((MateonException) e).getErrorCode())
                .isEqualTo(ErrorCode.STORAGE_QUOTA_EXCEEDED);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}

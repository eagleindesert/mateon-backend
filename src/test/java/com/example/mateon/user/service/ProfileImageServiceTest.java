package com.example.mateon.user.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.storage.BucketCapacityGuard;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 접수 단계의 규약을 고정한다. 핵심은 <b>무엇을 동기로 거절하고 무엇을 워커에 넘기는가</b>다.
 *
 * <p>
 * 형식·크기 검증이 여기서 일어나지 않으면 400 을 받을 수 있는 요청이 200 으로 접수되고,
 * 실패는 로그에만 남아 사용자는 이유를 알 수 없게 된다.
 */
class ProfileImageServiceTest {

    private static final long USER_ID = 42L;
    private static final String OLD_URL
      = "https://objectstorage.ap-chuncheon-1.oraclecloud.com/n/ns/b/mateon/o/profile-images/2026/06/old.png";

    private UserRepository userRepository;
    private ProfileImageWorker worker;
    private ProfileImageService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        worker = mock(ProfileImageWorker.class);
        // 용량 가드는 기본 mock 이라 항상 통과한다. 한도 동작 자체는 BucketCapacityGuardTest 가 본다.
        service = new ProfileImageService(userRepository, worker, mock(BucketCapacityGuard.class));

        when(userRepository.existsById(USER_ID)).thenReturn(true);
    }

    private MultipartFile image(String filename) {
        return new MockMultipartFile("image", filename, "image/png", new byte[]{1, 2, 3});
    }

    private User user(String profileImageUrl) {
        return User.builder().id(USER_ID).name("김루미").profileImageUrl(profileImageUrl).build();
    }

    @Test
    @DisplayName("검증을 통과하면 워커에 검증된 바이트와 확장자를 넘긴다")
    void handsValidatedImageToWorker() {
        service.upload(USER_ID, image("사진.JPG"));

        // 확장자는 소문자로, content-type 은 확장자에서 정한 값으로 넘어가야 한다.
        verify(worker).replaceProfileImage(eq(USER_ID), any(byte[].class), eq("jpg"), eq("image/jpeg"));
    }

    @Test
    @DisplayName("형식이 어긋난 파일은 워커를 부르기 전에 400 으로 거절한다")
    void rejectsInvalidFileSynchronously() {
        assertThatThrownBy(() -> service.upload(USER_ID, image("사진.gif")))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_IMAGE_FILE);

        verifyNoInteractions(worker);
    }

    @Test
    @DisplayName("한도를 넘는 파일은 워커를 부르기 전에 413 으로 거절한다")
    void rejectsOversizedFileSynchronously() {
        MultipartFile huge = new MockMultipartFile(
          "image", "사진.png", "image/png", new byte[10 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> service.upload(USER_ID, huge))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.IMAGE_TOO_LARGE);

        verifyNoInteractions(worker);
    }

    @Test
    @DisplayName("없는 유저의 업로드는 404 이고 워커를 부르지 않는다")
    void rejectsUploadForUnknownUser() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.upload(USER_ID, image("사진.png")))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verifyNoInteractions(worker);
    }

    @Test
    @DisplayName("삭제는 현재 URL 을 워커에 넘긴다")
    void handsCurrentUrlToWorkerOnDelete() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(OLD_URL)));

        service.delete(USER_ID);

        verify(worker).deleteObjectThenClearUrl(USER_ID, OLD_URL);
    }

    @Test
    @DisplayName("이미 사진이 없으면 아무것도 하지 않고 성공한다 (멱등)")
    void deleteIsIdempotentWhenNoImage() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(null)));

        assertThatCode(() -> service.delete(USER_ID)).doesNotThrowAnyException();

        verifyNoInteractions(worker);
    }

    @Test
    @DisplayName("없는 유저의 삭제는 404")
    void rejectsDeleteForUnknownUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(USER_ID))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verifyNoInteractions(worker);
    }

    @Test
    @DisplayName("접수 단계에서는 DB 의 URL 을 직접 건드리지 않는다 (버킷 작업과 순서를 맞춰야 한다)")
    void doesNotTouchUrlWhileAccepting() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(OLD_URL)));

        service.upload(USER_ID, image("사진.png"));
        service.delete(USER_ID);

        verify(userRepository, never()).updateProfileImageUrl(anyLong(), anyString(), any());
        verify(userRepository, never()).clearProfileImageUrlIfMatches(anyLong(), anyString(), any());
    }
}

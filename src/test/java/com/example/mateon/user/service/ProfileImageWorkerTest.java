package com.example.mateon.user.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.storage.ObjectStorageService;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 버킷과 DB 를 오가는 순서를 고정한다. 이 워커의 값은 순서에 있다.
 *
 * <p>
 * 지키는 규칙 셋. (1) 교체는 <b>이전 객체 삭제 → 업로드</b> 순서다 — 뒤집으면 삭제가 실패했을 때
 * 방금 올린 객체가 고아로 남는다. (2) 지운 객체의 URL 은 즉시 DB 에서 끊는다 — 남겨 두면 프론트가
 * 깨진 이미지를 그린다. (3) 실패는 밖으로 던지지 않는다 — {@code @Async void} 라 받는 곳이 없고,
 * 사용자는 이미 200 을 받았다.
 */
class ProfileImageWorkerTest {

    private static final long USER_ID = 42L;
    private static final String BUCKET_PREFIX
      = "https://objectstorage.ap-chuncheon-1.oraclecloud.com/n/ns/b/mateon/o/";
    private static final String OLD_URL = BUCKET_PREFIX + "profile-images/2026/06/old.png";
    private static final String NEW_URL = BUCKET_PREFIX + "profile-images/2026/07/new.png";

    private UserRepository userRepository;
    private ObjectStorageService objectStorageService;
    private ProfileImageWorker worker;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        objectStorageService = mock(ObjectStorageService.class);
        worker = new ProfileImageWorker(userRepository, objectStorageService);

        when(objectStorageService.upload(anyString(), any(), anyString())).thenReturn(NEW_URL);
        when(userRepository.clearProfileImageUrlIfMatches(anyLong(), anyString(), any())).thenReturn(1);
    }

    private void givenUserWithImage(String url) {
        when(userRepository.findById(USER_ID)).thenReturn(
          Optional.of(User.builder().id(USER_ID).name("김루미").profileImageUrl(url).build()));
    }

    @Test
    @DisplayName("첫 업로드는 지울 게 없으니 바로 올리고 URL 을 기록한다")
    void uploadsWithoutDeleteOnFirstImage() {
        givenUserWithImage(null);

        worker.replaceProfileImage(USER_ID, new byte[]{1, 2, 3}, "png", "image/png");

        verify(objectStorageService, never()).delete(anyString());
        verify(userRepository).updateProfileImageUrl(eq(USER_ID), eq(NEW_URL), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("객체 키는 연/월로 나뉘고 확장자를 유지한다")
    void buildsDatePartitionedKey() {
        givenUserWithImage(null);

        worker.replaceProfileImage(USER_ID, new byte[]{1}, "jpg", "image/jpeg");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(objectStorageService).upload(key.capture(), any(), eq("image/jpeg"));
        assertThat(key.getValue()).matches("profile-images/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg");
    }

    @Test
    @DisplayName("재업로드는 '이전 객체 삭제 → URL 끊기 → 업로드 → 새 URL 기록' 순서로 진행한다")
    void replacesInStrictOrder() {
        givenUserWithImage(OLD_URL);

        worker.replaceProfileImage(USER_ID, new byte[]{1}, "png", "image/png");

        InOrder order = inOrder(objectStorageService, userRepository);
        order.verify(objectStorageService).delete(OLD_URL);
        order.verify(userRepository).clearProfileImageUrlIfMatches(eq(USER_ID), eq(OLD_URL), any());
        order.verify(objectStorageService).upload(anyString(), any(), anyString());
        order.verify(userRepository).updateProfileImageUrl(eq(USER_ID), eq(NEW_URL), any());
    }

    @Test
    @DisplayName("이전 객체 삭제가 실패하면 업로드하지 않는다 (고아 객체를 만들지 않는다)")
    void abortsReplaceWhenDeleteFails() {
        givenUserWithImage(OLD_URL);
        doThrow(new MateonException(ErrorCode.IMAGE_DELETE_FAILED))
          .when(objectStorageService).delete(OLD_URL);

        assertThatCode(() -> worker.replaceProfileImage(USER_ID, new byte[]{1}, "png", "image/png"))
          .doesNotThrowAnyException();

        verify(objectStorageService, never()).upload(anyString(), any(), anyString());
        // 이전 사진이 그대로 보이는 상태로 남아야 한다 — URL 을 끊으면 지우지도 못한 객체를
        // 가리키는 참조까지 사라져 정리할 방법이 없어진다.
        verify(userRepository, never()).clearProfileImageUrlIfMatches(anyLong(), anyString(), any());
        verify(userRepository, never()).updateProfileImageUrl(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("업로드가 실패하면 URL 은 비워진 상태로 남고 예외는 밖으로 나가지 않는다")
    void keepsUrlClearedWhenUploadFails() {
        givenUserWithImage(OLD_URL);
        doThrow(new MateonException(ErrorCode.IMAGE_UPLOAD_FAILED))
          .when(objectStorageService).upload(anyString(), any(), anyString());

        assertThatCode(() -> worker.replaceProfileImage(USER_ID, new byte[]{1}, "png", "image/png"))
          .doesNotThrowAnyException();

        verify(userRepository).clearProfileImageUrlIfMatches(eq(USER_ID), eq(OLD_URL), any());
        verify(userRepository, never()).updateProfileImageUrl(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("그 사이 유저가 사라지면 아무것도 하지 않는다")
    void skipsWhenUserIsGone() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        worker.replaceProfileImage(USER_ID, new byte[]{1}, "png", "image/png");

        verify(objectStorageService, never()).upload(anyString(), any(), anyString());
        verify(objectStorageService, never()).delete(anyString());
    }

    @Test
    @DisplayName("삭제는 버킷 객체를 지운 뒤에만 URL 을 비운다")
    void clearsUrlOnlyAfterObjectIsGone() {
        worker.deleteObjectThenClearUrl(USER_ID, OLD_URL);

        InOrder order = inOrder(objectStorageService, userRepository);
        order.verify(objectStorageService).delete(OLD_URL);
        order.verify(userRepository).clearProfileImageUrlIfMatches(eq(USER_ID), eq(OLD_URL), any());
    }

    @Test
    @DisplayName("삭제가 실패하면 DB 를 건드리지 않는다 (사진이 그대로 보이는 일관 상태)")
    void keepsUrlWhenDeleteFails() {
        doThrow(new MateonException(ErrorCode.IMAGE_DELETE_FAILED))
          .when(objectStorageService).delete(OLD_URL);

        assertThatCode(() -> worker.deleteObjectThenClearUrl(USER_ID, OLD_URL))
          .doesNotThrowAnyException();

        verify(userRepository, never()).clearProfileImageUrlIfMatches(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("삭제 중에 새 사진이 올라왔으면 그 URL 을 지우지 않는다")
    void doesNotClearUrlChangedMidFlight() {
        // CAS 는 쿼리가 수행한다. 0 행이 돌아오는 건 "그 사이 URL 이 바뀌었다"는 뜻이고,
        // 워커는 이를 실패로 보지 않고 넘어가야 한다 (새 사진을 지우면 안 된다).
        when(userRepository.clearProfileImageUrlIfMatches(eq(USER_ID), eq(OLD_URL), any())).thenReturn(0);

        assertThatCode(() -> worker.deleteObjectThenClearUrl(USER_ID, OLD_URL))
          .doesNotThrowAnyException();

        verify(userRepository, never()).updateProfileImageUrl(anyLong(), anyString(), any());
    }
}

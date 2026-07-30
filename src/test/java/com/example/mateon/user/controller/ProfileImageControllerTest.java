package com.example.mateon.user.controller;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.user.service.ProfileImageService;
import com.example.mateon.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로필 이미지 업로드·삭제가 밖으로 내보이는 계약을 고정한다.
 *
 * <p>고정하고 싶은 것은 두 가지다. (1) 응답에 이미지 URL 이 없다 — 버킷 작업이 비동기라 이 시점에
 * 줄 URL 이 없고, 프론트는 유저 조회로 확인해야 한다. (2) 파일 문제는 200 접수가 아니라 4xx 로
 * 나간다 — 비동기 실패는 사용자에게 전할 길이 없으니, 미리 걸러낼 수 있는 건 여기서 걸러야 한다.
 */
class ProfileImageControllerTest {

    private static final long USER_ID = 1L;

    private ProfileImageService profileImageService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        profileImageService = mock(ProfileImageService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UserController(mock(UserService.class), profileImageService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), "");
    }

    private MockMultipartFile imagePart(String partName) {
        return new MockMultipartFile(partName, "profile.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("업로드는 200 과 진행 중 안내를 주고, URL 은 담지 않는다")
    void uploadReturnsAcceptedMessageWithoutUrl() throws Exception {
        mockMvc.perform(multipart("/api/users/me/profile-image")
                        .file(imagePart("image"))
                        .principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("프로필 이미지 업로드가 진행 중입니다."))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(profileImageService).upload(eq(USER_ID), any());
    }

    @Test
    @DisplayName("파트 이름이 image 가 아니면 400 (핸들러가 없으면 500 이 된다)")
    void uploadRejectsWrongPartName() throws Exception {
        mockMvc.perform(multipart("/api/users/me/profile-image")
                        .file(imagePart("file"))
                        .principal(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("형식이 어긋난 파일은 접수되지 않고 400 으로 나간다")
    void uploadSurfacesValidationFailure() throws Exception {
        doThrow(new MateonException(ErrorCode.INVALID_IMAGE_FILE))
                .when(profileImageService).upload(eq(USER_ID), any());

        mockMvc.perform(multipart("/api/users/me/profile-image")
                        .file(imagePart("image"))
                        .principal(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_IMAGE_FILE.getMessage()));
    }

    @Test
    @DisplayName("한도를 넘는 파일은 413 으로 나간다")
    void uploadSurfacesSizeFailure() throws Exception {
        doThrow(new MateonException(ErrorCode.IMAGE_TOO_LARGE))
                .when(profileImageService).upload(eq(USER_ID), any());

        mockMvc.perform(multipart("/api/users/me/profile-image")
                        .file(imagePart("image"))
                        .principal(auth()))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    @DisplayName("삭제는 200 과 접수 안내를 준다")
    void deleteReturnsAcceptedMessage() throws Exception {
        mockMvc.perform(delete("/api/users/me/profile-image").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("프로필 이미지 삭제가 요청되었습니다."));

        verify(profileImageService).delete(USER_ID);
    }
}

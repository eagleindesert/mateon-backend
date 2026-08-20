package com.example.mateon.auth.controller;

import com.example.mateon.auth.dto.KakaoLoginRequest;
import com.example.mateon.auth.dto.SchoolEmailRequest;
import com.example.mateon.auth.dto.SchoolEmailVerifyRequest;
import com.example.mateon.auth.dto.TokenResponse;
import com.example.mateon.auth.service.AuthService;
import com.example.mateon.auth.service.KakaoLoginService;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증 API 의 응답 계약을 고정한다.
 *
 * <p>
 * 가장 자주 깨질 만한 건 <b>JSON 키 이름</b>이다. 프론트는 {@code data.accessToken},
 * {@code data.verificationToken} 을 그대로 읽는데, 서버에서 DTO 필드명을 다듬는 것만으로
 * 로그인 화면 전체가 조용히 멈춘다 (HTTP 는 200 이고 값만 undefined 가 된다).
 *
 * <p>
 * 두 번째는 <b>로그인 실패가 400 이라는 사실</b>이다. 401 일 것 같지만
 * {@link ErrorCode#INVALID_CREDENTIALS} 가 상태를 지정하지 않아 기본값 400 으로 나간다.
 * 프론트가 이미 400 을 보고 "아이디/비밀번호를 확인하세요" 를 띄우고 있으므로 바꾸면 깨진다.
 *
 * <p>
 * 세 번째는 학교 인증 경로가 <b>principal 문자열을 Long 으로 변환해</b> 서비스에 넘긴다는
 * 점이다. String 을 그대로 넘기면 컴파일은 되지 않지만, 반대로 컨트롤러가 userId 를 잘못 읽어
 * 다른 사람의 학교 인증을 바꾸는 사고는 타입만으로 막히지 않는다 — 캡터로 값까지 확인한다.
 *
 * <p>
 * 넷째, 카카오 경로는 {@link AuthService} 가 아니라 {@link KakaoLoginService} 로 가야
 * 한다. 트랜잭션 밖에서 외부 호출을 하려고 빈을 나눠 둔 것이라, 여기서 대상이 바뀌면 그 설계가
 * 무력화된다.
 *
 * <p>
 * <b>마지막으로, 반환할 데이터가 없는 엔드포인트의 봉투가 나머지와 다르다는 사실을
 * 그대로 고정한다.</b> {@code ApiResponse.success("인증코드가 발송되었습니다.")} 는 인자가
 * 하나라 {@code success(T data)} 오버로드에 잡힌다 — 그래서 안내 문구가 {@code message} 가
 * 아니라 {@code data} 로 들어가고 {@code message} 는 {@code "성공"} 이 된다. 반면 토큰을
 * 함께 주는 엔드포인트는 {@code success(message, data)} 를 써서 문구가 {@code message} 에
 * 있다. 즉 같은 API 군 안에서 안내 문구의 위치가 엔드포인트마다 다르다.
 *
 * <p>
 * 의도된 설계로 보기는 어렵지만, 프론트가 이미 이 응답들을 받아 쓰고 있으므로 테스트는
 * "고쳐야 할 모습"이 아니라 <b>현재 나가는 실제 모습</b>을 고정한다. 나중에 봉투를 통일하기로
 * 결정하면 여기 단언들이 한꺼번에 빨개지므로, 어떤 엔드포인트가 영향을 받는지 목록이 저절로 나온다.
 * 해당 경로: {@code /email/request}, {@code /school/email/request},
 * {@code /school/email/verify}, {@code /password/change}, {@code /logout}.
 */
class AuthControllerTest {

    private static final long USER_ID = 11L;

    private AuthService authService;
    private KakaoLoginService kakaoLoginService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        kakaoLoginService = mock(KakaoLoginService.class);
        mockMvc = MockMvcBuilders
          .standaloneSetup(new AuthController(authService, kakaoLoginService))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();
    }

    @Nested
    @DisplayName("이메일 인증")
    class EmailVerification {

        @Test
        @DisplayName("코드 요청의 안내 문구는 message 가 아니라 data 로 나간다 (단일 인자 오버로드)")
        void requestCode() throws Exception {
            mockMvc.perform(json("/api/auth/email/request", "{\"email\":\"a@b.ac.kr\"}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.message").value("성공"))
              .andExpect(jsonPath("$.data").value("인증코드가 발송되었습니다."));
        }

        @Test
        @DisplayName("검증 성공 응답의 키는 data.verificationToken 이다 (가입 요청에 그대로 넣는 값)")
        void verifyReturnsTicketUnderExpectedKey() throws Exception {
            when(authService.verifyEmail(any())).thenReturn("ticket-abc");

            mockMvc.perform(json("/api/auth/email/verify",
              "{\"email\":\"a@b.ac.kr\",\"code\":\"123456\"}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.verificationToken").value("ticket-abc"));
        }

        @Test
        @DisplayName("코드가 6자리가 아니면 서비스까지 가지 않고 400 이다")
        void malformedCodeIsRejectedByValidation() throws Exception {
            mockMvc.perform(json("/api/auth/email/verify",
              "{\"email\":\"a@b.ac.kr\",\"code\":\"12\"}"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.data.code").value("인증코드는 6자리 숫자여야 합니다."));

            verify(authService, never()).verifyEmail(any());
        }

        @Test
        @DisplayName("쿨다운에 걸리면 429 가 그대로 나간다")
        void cooldownIs429() throws Exception {
            org.mockito.Mockito.doThrow(new MateonException(ErrorCode.EMAIL_REQUEST_TOO_FREQUENT))
              .when(authService).requestEmailVerification(any());

            mockMvc.perform(json("/api/auth/email/request", "{\"email\":\"a@b.ac.kr\"}"))
              .andExpect(status().isTooManyRequests())
              .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Nested
    @DisplayName("학교 이메일 인증 — principal 을 userId 로 정확히 옮긴다")
    class SchoolEmail {

        @Test
        @DisplayName("요청은 인증 주체의 userId 를 Long 으로 서비스에 넘긴다")
        void passesAuthenticatedUserId() throws Exception {
            mockMvc.perform(json("/api/auth/school/email/request",
              "{\"schoolEmail\":\"a@b.ac.kr\"}").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data").value("학교 이메일로 인증코드가 발송되었습니다."));

            ArgumentCaptor<Long> userId = ArgumentCaptor.forClass(Long.class);
            verify(authService).requestSchoolEmailVerification(userId.capture(), any(SchoolEmailRequest.class));
            assertThat(userId.getValue()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("검증도 같은 userId 로 간다")
        void verifyPassesAuthenticatedUserId() throws Exception {
            mockMvc.perform(json("/api/auth/school/email/verify",
              "{\"schoolEmail\":\"a@b.ac.kr\",\"code\":\"123456\"}").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data").value("학교 인증이 완료되었습니다."));

            verify(authService).verifySchoolEmail(eq(USER_ID), any(SchoolEmailVerifyRequest.class));
        }

        @Test
        @DisplayName("이미 다른 계정이 쓰는 학교 이메일이면 400 이다")
        void alreadyUsed() throws Exception {
            org.mockito.Mockito.doThrow(new MateonException(ErrorCode.SCHOOL_EMAIL_ALREADY_USED))
              .when(authService).verifySchoolEmail(anyLong(), any());

            mockMvc.perform(json("/api/auth/school/email/verify",
              "{\"schoolEmail\":\"a@b.ac.kr\",\"code\":\"123456\"}").principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.SCHOOL_EMAIL_ALREADY_USED.getMessage()));
        }
    }

    @Nested
    @DisplayName("회원가입 · 로그인 · 재발급 — 토큰 응답 키를 고정한다")
    class Tokens {

        @Test
        @DisplayName("회원가입 응답에 accessToken/refreshToken/tokenType/expiresIn 이 전부 있다")
        void signupTokenShape() throws Exception {
            when(authService.signup(any())).thenReturn(tokens());

            mockMvc.perform(json("/api/auth/signup", signupBody()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
              .andExpect(jsonPath("$.data.accessToken").value("access"))
              .andExpect(jsonPath("$.data.refreshToken").value("refresh"))
              .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
              .andExpect(jsonPath("$.data.expiresIn").value(3600));
        }

        @Test
        @DisplayName("로그인 성공은 200 + '로그인 성공'")
        void loginSuccess() throws Exception {
            when(authService.login(any())).thenReturn(tokens());

            mockMvc.perform(json("/api/auth/login",
              "{\"email\":\"a@b.ac.kr\",\"password\":\"password12\"}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.message").value("로그인 성공"))
              .andExpect(jsonPath("$.data.accessToken").value("access"));
        }

        @Test
        @DisplayName("로그인 실패는 401 이 아니라 400 이다 — 프론트가 이미 400 으로 처리하고 있다")
        void loginFailureIs400NotUnauthorized() throws Exception {
            when(authService.login(any())).thenThrow(new MateonException(ErrorCode.INVALID_CREDENTIALS));

            mockMvc.perform(json("/api/auth/login",
              "{\"email\":\"a@b.ac.kr\",\"password\":\"wrong-password\"}"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_CREDENTIALS.getMessage()));
        }

        @Test
        @DisplayName("재발급 성공은 200 + 갱신된 액세스 토큰")
        void refresh() throws Exception {
            when(authService.refreshToken(any())).thenReturn(tokens());

            mockMvc.perform(json("/api/auth/token/refresh", "{\"refreshToken\":\"r\"}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.message").value("토큰이 갱신되었습니다."))
              .andExpect(jsonPath("$.data.accessToken").value("access"));
        }

        @Test
        @DisplayName("만료된 리프레시 토큰은 400 이다 (401 로 바뀌면 프론트가 재로그인 유도를 못 한다)")
        void expiredRefreshToken() throws Exception {
            when(authService.refreshToken(any())).thenThrow(new MateonException(ErrorCode.TOKEN_EXPIRED));

            mockMvc.perform(json("/api/auth/token/refresh", "{\"refreshToken\":\"r\"}"))
              .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("카카오 로그인은 KakaoLoginService 로 간다")
    class Kakao {

        @Test
        @DisplayName("AuthService 가 아니라 KakaoLoginService 를 부른다 (트랜잭션 밖 외부 호출 설계)")
        void routesToKakaoLoginService() throws Exception {
            when(kakaoLoginService.login(any())).thenReturn(tokens());

            mockMvc.perform(json("/api/auth/social/kakao", "{\"accessToken\":\"kakao-token\"}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.message").value("카카오 로그인 성공"))
              .andExpect(jsonPath("$.data.accessToken").value("access"));

            ArgumentCaptor<KakaoLoginRequest> captor = ArgumentCaptor.forClass(KakaoLoginRequest.class);
            verify(kakaoLoginService).login(captor.capture());
            assertThat(captor.getValue().getAccessToken()).isEqualTo("kakao-token");
            verify(authService, never()).kakaoLogin(any());
        }

        @Test
        @DisplayName("카카오 인증 실패는 400 으로 나간다")
        void kakaoFailure() throws Exception {
            when(kakaoLoginService.login(any())).thenThrow(new MateonException(ErrorCode.KAKAO_AUTH_FAILED));

            mockMvc.perform(json("/api/auth/social/kakao", "{\"accessToken\":\"bad\"}"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.KAKAO_AUTH_FAILED.getMessage()));
        }
    }

    @Nested
    @DisplayName("비밀번호 변경 · 로그아웃")
    class PasswordAndLogout {

        @Test
        @DisplayName("변경 성공 안내는 '다시 로그인' 을 포함한다 (리프레시 토큰이 지워지기 때문)")
        void changePassword() throws Exception {
            mockMvc.perform(json("/api/auth/password/change", """
                    {"email":"a@b.ac.kr","currentPassword":"old-password",
                     "newPassword":"new-password","newPasswordConfirm":"new-password"}
                    """))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data").value("비밀번호가 변경되었습니다. 다시 로그인해주세요."));
        }

        @Test
        @DisplayName("새 비밀번호가 10자 미만이면 서비스까지 가지 않는다")
        void tooShortPassword() throws Exception {
            mockMvc.perform(json("/api/auth/password/change", """
                    {"email":"a@b.ac.kr","currentPassword":"old-password",
                     "newPassword":"short","newPasswordConfirm":"short"}
                    """))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.data.newPassword").value("비밀번호는 10-20자리 이내로 입력해주세요."));

            verify(authService, never()).changePassword(any());
        }

        @Test
        @DisplayName("로그아웃은 200 이다")
        void logout() throws Exception {
            mockMvc.perform(json("/api/auth/logout", "{\"email\":\"a@b.ac.kr\"}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data").value("로그아웃되었습니다."));
        }

        @Test
        @DisplayName("데이터 없는 응답과 토큰 응답의 문구 위치가 서로 다르다 (현재 상태를 나란히 고정)")
        void messagePlacementDiffersBetweenEndpoints() throws Exception {
            when(authService.login(any())).thenReturn(tokens());

            // 토큰이 있는 쪽: 문구가 message 에 있고 data 는 페이로드다.
            mockMvc.perform(json("/api/auth/login",
              "{\"email\":\"a@b.ac.kr\",\"password\":\"password12\"}"))
              .andExpect(jsonPath("$.message").value("로그인 성공"))
              .andExpect(jsonPath("$.data.accessToken").exists());

            // 데이터가 없는 쪽: message 는 "성공" 고정이고 문구가 data 로 내려간다.
            mockMvc.perform(json("/api/auth/logout", "{\"email\":\"a@b.ac.kr\"}"))
              .andExpect(jsonPath("$.message").value("성공"))
              .andExpect(jsonPath("$.data").value("로그아웃되었습니다."));
        }
    }

    // --- 헬퍼 ---------------------------------------------------------------
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private TokenResponse tokens() {
        return TokenResponse.builder()
          .accessToken("access").refreshToken("refresh")
          .tokenType("Bearer").expiresIn(3600)
          .build();
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }

    private String signupBody() {
        return """
                {"email":"a@b.ac.kr","verificationToken":"ticket","password":"password12",
                 "passwordConfirm":"password12","name":"김학생"}
                """;
    }
}

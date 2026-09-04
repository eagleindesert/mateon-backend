package com.example.mateon.auth.service;

import com.example.mateon.auth.domain.EmailVerification;
import com.example.mateon.auth.domain.RefreshToken;
import com.example.mateon.auth.dto.ChangePasswordRequest;
import com.example.mateon.auth.dto.LoginRequest;
import com.example.mateon.auth.dto.LogoutRequest;
import com.example.mateon.auth.dto.RefreshTokenRequest;
import com.example.mateon.auth.dto.SignupRequest;
import com.example.mateon.auth.dto.TokenResponse;
import com.example.mateon.auth.jwt.JwtTokenProvider;
import com.example.mateon.auth.repository.EmailVerificationRepository;
import com.example.mateon.auth.repository.RefreshTokenRepository;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.support.TestJwt;
import com.example.mateon.user.domain.AuthProvider;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 회원가입·로그인·토큰 재발급의 규칙을 고정한다.
 *
 * <p>
 * 가장 값비싼 규칙은 <b>인증 티켓</b>이다. "이 이메일이 verified 다"라는 사실만으로 가입을
 * 허용하면, 남이 인증을 마쳐 둔 이메일을 제3자가 가로채 그 주소로 계정을 만들 수 있다. 그래서
 * 인증을 완료한 주체에게만 준 일회용 티켓을 가입 시 다시 제출하게 하고, 티켓에는 30분 수명을
 * 둔다. 티켓 검사나 TTL 이 사라져도 정상 가입은 계속 되기 때문에 테스트 없이는 알아채기 어렵다.
 *
 * <p>
 * 다음은 <b>계정 열거 방지</b>다. 없는 이메일로 로그인하든 비밀번호가 틀리든 응답이 같아야
 * 한다 — {@code USER_NOT_FOUND}(404)가 새어 나가면 그것만으로 "가입된 이메일 목록"을 만들 수 있다.
 *
 * <p>
 * 마지막은 <b>리프레시 토큰이 유저당 한 행</b>이라는 것이다. delete→insert 로 바꾸면
 * Hibernate 의 flush 순서 때문에 INSERT 가 DELETE 보다 먼저 나가 UNIQUE 제약과 충돌한다.
 */
class AuthServiceTokenTest {

    private static final String EMAIL = "student@univ.ac.kr";
    private static final String TICKET = "ticket-uuid";
    private static final long USER_ID = 5L;

    private UserRepository userRepository;
    private EmailVerificationRepository emailVerificationRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private PasswordEncoder passwordEncoder;
    private ApplicationEventPublisher eventPublisher;
    private JwtTokenProvider jwtTokenProvider;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emailVerificationRepository = mock(EmailVerificationRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        jwtTokenProvider = TestJwt.provider();

        authService = new AuthService(
          userRepository,
          emailVerificationRepository,
          refreshTokenRepository,
          passwordEncoder,
          eventPublisher,
          jwtTokenProvider,
          TestJwt.properties());

        // 저장 시 id 가 채워지는 IDENTITY 동작을 흉내낸다. 없으면 토큰 subject 가 "null" 이 된다.
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            if (user.getId() == null) {
                user.setId(USER_ID);
            }
            return user;
        });
    }

    @Nested
    @DisplayName("회원가입")
    class Signup {

        @Test
        @DisplayName("정상 가입은 LOCAL provider + 학교 이메일 확정 + 인코딩된 비밀번호로 저장된다")
        void createsLocalVerifiedUser() {
            givenValidTicket();
            when(passwordEncoder.encode("password12")).thenReturn("encoded");

            authService.signup(signupRequest());

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            User saved = captor.getValue();

            assertThat(saved.getEmail()).isEqualTo(EMAIL);
            assertThat(saved.getPassword()).isEqualTo("encoded");
            assertThat(saved.getProvider()).isEqualTo(AuthProvider.LOCAL);
            // 로컬 가입은 .ac.kr 로 선행 인증했으므로 학교 인증을 다시 요구하지 않는다.
            assertThat(saved.getSchoolEmail()).isEqualTo(EMAIL);
            assertThat(saved.isSchoolVerified()).isTrue();
        }

        @Test
        @DisplayName("비밀번호 확인이 다르면 PASSWORD_MISMATCH")
        void passwordConfirmMismatch() {
            SignupRequest request = signupRequest();
            request.setPasswordConfirm("different12");

            assertThatThrownBy(() -> authService.signup(request))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_MISMATCH);
        }

        @Test
        @DisplayName("이미 가입된 이메일이면 EMAIL_ALREADY_EXISTS")
        void duplicateEmail() {
            when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

            assertThatThrownBy(() -> authService.signup(signupRequest()))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("인증 이력이 아예 없으면 EMAIL_NOT_VERIFIED")
        void noVerificationRow() {
            when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.signup(signupRequest()))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
        }

        @Test
        @DisplayName("티켓이 틀리면 가입할 수 없다 — 남이 인증해 둔 이메일 선점을 막는 유일한 장치")
        void wrongTicketCannotClaimVerifiedEmail() {
            givenValidTicket();
            SignupRequest request = signupRequest();
            request.setVerificationToken("attacker-guess");

            assertThatThrownBy(() -> authService.signup(request))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERIFICATION_TOKEN);

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("티켓 발급 29분 뒤는 통과한다 (TTL 경계가 30분이라는 사실을 고정)")
        void ticketJustInsideTtl() {
            givenTicketIssuedAt(LocalDateTime.now().minusMinutes(29));

            assertThat(authService.signup(signupRequest())).isNotNull();
        }

        @Test
        @DisplayName("티켓 발급 31분 뒤는 만료다 — 인증 상태를 영구 선점하지 못하게 한다")
        void ticketExpiresAfterTtl() {
            givenTicketIssuedAt(LocalDateTime.now().minusMinutes(31));

            assertThatThrownBy(() -> authService.signup(signupRequest()))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERIFICATION_TOKEN);
        }

        @Test
        @DisplayName("expiresIn 은 밀리초가 아니라 초다 (1000 배 실수는 눈에 띄지 않는다)")
        void expiresInIsSeconds() {
            givenValidTicket();

            TokenResponse response = authService.signup(signupRequest());

            assertThat(response.getExpiresIn()).isEqualTo(TestJwt.EXPIRATION_MS / 1000);
            assertThat(response.getTokenType()).isEqualTo("Bearer");
            assertThat(response.getAccessToken()).isNotBlank();
            assertThat(response.getRefreshToken()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("로그인 — 실패 사유를 구분해 알려주지 않는다")
    class Login {

        @Test
        @DisplayName("정상 로그인은 액세스/리프레시 토큰을 준다")
        void success() {
            User user = localUser("encoded");
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("password12", "encoded")).thenReturn(true);

            TokenResponse response = authService.login(loginRequest("password12"));

            assertThat(jwtTokenProvider.getUserIdFromToken(response.getAccessToken())).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("없는 이메일도 USER_NOT_FOUND 가 아니라 INVALID_CREDENTIALS 다 (계정 열거 방지)")
        void unknownEmailLooksLikeWrongPassword() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(loginRequest("password12")))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("비밀번호가 틀리면 INVALID_CREDENTIALS")
        void wrongPassword() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(localUser("encoded")));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> authService.login(loginRequest("wrong-password")))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("비밀번호가 없는 소셜 유저는 인코더를 태우지도 않는다 (null 매칭 시 NPE 방지)")
        void socialUserWithoutPasswordShortCircuits() {
            User social = User.builder()
              .id(USER_ID).email(EMAIL).name("김카카오")
              .provider(AuthProvider.KAKAO).providerId("kakao-1")
              .build();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(social));

            assertThatThrownBy(() -> authService.login(loginRequest("anything123")))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);

            verify(passwordEncoder, never()).matches(anyString(), any());
        }
    }

    @Nested
    @DisplayName("리프레시 토큰")
    class Refresh {

        @Test
        @DisplayName("성공해도 리프레시 토큰은 회전하지 않고 그대로 돌려준다")
        void doesNotRotateRefreshToken() {
            String refresh = jwtTokenProvider.createRefreshToken(USER_ID);
            when(refreshTokenRepository.findByToken(refresh))
              .thenReturn(Optional.of(storedToken(refresh, LocalDateTime.now().plusDays(7))));

            TokenResponse response = authService.refreshToken(refreshRequest(refresh));

            assertThat(response.getRefreshToken()).isEqualTo(refresh);
            assertThat(jwtTokenProvider.getUserIdFromToken(response.getAccessToken())).isEqualTo(USER_ID);
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("서명이 깨진 토큰은 DB 를 보지도 않고 INVALID_TOKEN")
        void invalidSignature() {
            assertThatThrownBy(() -> authService.refreshToken(refreshRequest("garbage")))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);

            verify(refreshTokenRepository, never()).findByToken(anyString());
        }

        @Test
        @DisplayName("액세스 토큰을 넣으면 서명이 유효해도 DB 를 보지 않고 INVALID_TOKEN — type 클레임이 다르다")
        void accessTokenIsNotARefreshToken() {
            String access = jwtTokenProvider.createAccessToken(USER_ID);

            assertThatThrownBy(() -> authService.refreshToken(refreshRequest(access)))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);

            verify(refreshTokenRepository, never()).findByToken(anyString());
        }

        @Test
        @DisplayName("서명은 유효하지만 저장되지 않은 토큰이면 TOKEN_NOT_FOUND (로그아웃된 토큰)")
        void notStored() {
            String refresh = jwtTokenProvider.createRefreshToken(USER_ID);
            when(refreshTokenRepository.findByToken(refresh)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refreshToken(refreshRequest(refresh)))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.TOKEN_NOT_FOUND);
        }

        @Test
        @DisplayName("DB 기준 만료면 그 행을 지우고 나서 TOKEN_EXPIRED — 지우지 않으면 죽은 행이 쌓인다")
        void expiredRowIsDeleted() {
            String refresh = jwtTokenProvider.createRefreshToken(USER_ID);
            RefreshToken stored = storedToken(refresh, LocalDateTime.now().minusDays(1));
            when(refreshTokenRepository.findByToken(refresh)).thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> authService.refreshToken(refreshRequest(refresh)))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.TOKEN_EXPIRED);

            verify(refreshTokenRepository).delete(stored);
        }
    }

    @Nested
    @DisplayName("리프레시 토큰 저장은 유저당 1행 upsert 다")
    class SaveRefreshToken {

        @Test
        @DisplayName("기존 행이 있으면 그 인스턴스를 rotate 해서 저장한다 (delete 하지 않는다)")
        void rotatesExistingRow() {
            User user = localUser("encoded");
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

            RefreshToken existing = storedToken("old-token", LocalDateTime.now().plusDays(1));
            when(refreshTokenRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

            TokenResponse response = authService.login(loginRequest("password12"));

            verify(refreshTokenRepository).save(existing);
            verify(refreshTokenRepository, never()).delete(any());
            assertThat(existing.getToken()).isEqualTo(response.getRefreshToken());
        }

        @Test
        @DisplayName("기존 행이 없으면 새로 만든다")
        void createsWhenAbsent() {
            User user = localUser("encoded");
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            when(refreshTokenRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            authService.login(loginRequest("password12"));

            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
            assertThat(captor.getValue().getExpiresAt()).isAfter(LocalDateTime.now());
        }
    }

    @Nested
    @DisplayName("비밀번호 변경 · 로그아웃")
    class PasswordAndLogout {

        @Test
        @DisplayName("변경에 성공하면 리프레시 토큰을 지운다 — 다른 기기 세션이 살아 있으면 안 된다")
        void changeRevokesRefreshToken() {
            User user = localUser("old-encoded");
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("old-password", "old-encoded")).thenReturn(true);
            when(passwordEncoder.encode("new-password")).thenReturn("new-encoded");

            authService.changePassword(changeRequest("old-password", "new-password", "new-password"));

            assertThat(user.getPassword()).isEqualTo("new-encoded");
            verify(refreshTokenRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("새 비밀번호 확인이 다르면 유저를 조회하지도 않는다")
        void confirmMismatchShortCircuits() {
            assertThatThrownBy(()
              -> authService.changePassword(changeRequest("old-password", "new-password", "typo")))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_MISMATCH);

            verify(userRepository, never()).findByEmail(anyString());
        }

        @Test
        @DisplayName("현재 비밀번호가 틀리면 저장하지 않는다")
        void wrongCurrentPassword() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(localUser("old-encoded")));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(()
              -> authService.changePassword(changeRequest("wrong", "new-password", "new-password")))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_MISMATCH);

            verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
        }

        @Test
        @DisplayName("로그아웃은 리프레시 토큰을 지운다")
        void logout() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(localUser("encoded")));

            LogoutRequest request = new LogoutRequest();
            request.setEmail(EMAIL);
            authService.logout(request);

            verify(refreshTokenRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("없는 이메일로 로그아웃하면 USER_NOT_FOUND")
        void logoutUnknownUser() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            LogoutRequest request = new LogoutRequest();
            request.setEmail(EMAIL);

            assertThatThrownBy(() -> authService.logout(request))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    private void givenValidTicket() {
        givenTicketIssuedAt(LocalDateTime.now().minusMinutes(1));
    }

    private void givenTicketIssuedAt(LocalDateTime verifiedAt) {
        EmailVerification verification = EmailVerification.builder()
          .email(EMAIL).code("123456")
          .expiresAt(LocalDateTime.now().plusMinutes(5))
          .build();
        verification.verify(TICKET);
        ReflectionTestUtils.setField(verification, "verifiedAt", verifiedAt);
        when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.of(verification));

        // 엔티티 쪽 계산과 서비스 쪽 TTL 이 같은 기준인지도 함께 확인해 둔다.
        assertThat(verification.isTicketValid(TICKET, Duration.ofMinutes(30)))
          .isEqualTo(verifiedAt.isAfter(LocalDateTime.now().minusMinutes(30)));
    }

    private User localUser(String encodedPassword) {
        return User.builder()
          .id(USER_ID).email(EMAIL).password(encodedPassword)
          .provider(AuthProvider.LOCAL).name("김학생")
          .schoolEmail(EMAIL).schoolVerified(true)
          .build();
    }

    private RefreshToken storedToken(String token, LocalDateTime expiresAt) {
        return RefreshToken.builder()
          .id(1L).token(token).userId(USER_ID).expiresAt(expiresAt)
          .build();
    }

    private SignupRequest signupRequest() {
        SignupRequest request = new SignupRequest();
        request.setEmail(EMAIL);
        request.setVerificationToken(TICKET);
        request.setPassword("password12");
        request.setPasswordConfirm("password12");
        request.setName("김학생");
        request.setSchool("한국대학교");
        return request;
    }

    private LoginRequest loginRequest(String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail(EMAIL);
        request.setPassword(password);
        return request;
    }

    private RefreshTokenRequest refreshRequest(String token) {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken(token);
        return request;
    }

    private ChangePasswordRequest changeRequest(String current, String next, String confirm) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setEmail(EMAIL);
        request.setCurrentPassword(current);
        request.setNewPassword(next);
        request.setNewPasswordConfirm(confirm);
        return request;
    }
}

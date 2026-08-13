package com.example.mateon.auth.service;

import com.example.mateon.auth.domain.EmailVerification;
import com.example.mateon.auth.dto.EmailRequest;
import com.example.mateon.auth.dto.EmailVerifyRequest;
import com.example.mateon.auth.dto.SchoolEmailRequest;
import com.example.mateon.auth.dto.SchoolEmailVerifyRequest;
import com.example.mateon.auth.repository.EmailVerificationRepository;
import com.example.mateon.auth.repository.RefreshTokenRepository;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.mail.event.VerificationCodeIssuedEvent;
import com.example.mateon.support.TestJwt;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이메일 인증(회원가입용 · 학교 인증용)의 규칙을 고정한다.
 *
 * <p>이 흐름은 메일이라는 외부 자원과 계정 신원이 동시에 걸려 있어, 조용히 깨지면 피해가 크다.
 * 여기서 붙잡는 건 크게 셋이다.
 *
 * <p><b>하나, 재요청 쿨다운.</b> 60초 안에 다시 요청하면 429 로 막는다. 이게 풀리면 남의
 * 이메일 주소로 메일 폭탄을 보낼 수 있고, 발송 계정이 스팸으로 차단되면 회원가입 전체가 멈춘다.
 * 경계값(59초/61초)을 함께 고정하는 이유는 부등호 방향이 뒤집혀도 "동작은 하는" 것처럼 보이기 때문이다.
 *
 * <p><b>둘, 재발급이 기존 행을 재사용한다는 것.</b> {@code email} 에 UNIQUE 가 걸려 있어서
 * id 를 빼먹고 새 행을 만들면 두 번째 요청부터 제약 위반으로 죽는다.
 *
 * <p><b>셋, 저장한 코드와 메일로 보낸 코드가 같다는 것.</b> 둘이 어긋나면 모든 사용자가
 * "절대 통과할 수 없는 코드"를 받게 되는데, 서버 로그에는 아무 에러도 남지 않는다.
 * 코드값 자체는 {@code SecureRandom} 이라 단정할 수 없으니 형태와 일치만 본다.
 */
class AuthServiceEmailVerificationTest {

    private static final String EMAIL = "student@univ.ac.kr";
    private static final long USER_ID = 1L;

    private UserRepository userRepository;
    private EmailVerificationRepository emailVerificationRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private PasswordEncoder passwordEncoder;
    private ApplicationEventPublisher eventPublisher;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emailVerificationRepository = mock(EmailVerificationRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        authService = new AuthService(
                userRepository,
                emailVerificationRepository,
                refreshTokenRepository,
                passwordEncoder,
                eventPublisher,
                TestJwt.provider(),
                TestJwt.properties());
    }

    @Nested
    @DisplayName("회원가입용 인증코드 요청")
    class RequestEmailVerification {

        @Test
        @DisplayName(".ac.kr 이 아닌 이메일은 거부한다 — 메일을 보내기 전에 막아야 의미가 있다")
        void rejectsNonSchoolDomain() {
            assertThatThrownBy(() -> authService.requestEmailVerification(emailRequest("me@gmail.com")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_EMAIL_DOMAIN);

            verify(emailVerificationRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("처음 요청이면 6자리 코드를 저장하고 발송 이벤트를 낸다")
        void issuesCode() {
            when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            authService.requestEmailVerification(emailRequest(EMAIL));

            EmailVerification saved = captureSaved();
            assertThat(saved.getEmail()).isEqualTo(EMAIL);
            assertThat(saved.getCode()).matches("\\d{6}");
            assertThat(saved.getVerified()).isFalse();
            assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("저장한 코드와 메일로 나가는 코드가 같다 — 어긋나면 아무도 인증을 통과할 수 없다")
        void savedCodeMatchesPublishedCode() {
            when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            authService.requestEmailVerification(emailRequest(EMAIL));

            ArgumentCaptor<VerificationCodeIssuedEvent> event =
                    ArgumentCaptor.forClass(VerificationCodeIssuedEvent.class);
            verify(eventPublisher).publishEvent(event.capture());

            assertThat(event.getValue().email()).isEqualTo(EMAIL);
            assertThat(event.getValue().code()).isEqualTo(captureSaved().getCode());
        }

        @Test
        @DisplayName("코드는 항상 6자리다 — 200회 발급해도 앞자리 0 이 잘리지 않는다 (%06d)")
        void codeIsAlwaysSixDigits() {
            // 1/10 확률로만 드러나는 제로패딩 버그를 결정적으로 잡으려면 반복이 필요하다.
            for (int i = 0; i < 200; i++) {
                reset(emailVerificationRepository, eventPublisher);
                when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

                authService.requestEmailVerification(emailRequest(EMAIL));

                assertThat(captureSaved().getCode()).matches("\\d{6}");
            }
        }

        @Test
        @DisplayName("마지막 발송 59초 뒤 재요청은 429 로 막는다 (메일 폭탄 방지)")
        void rejectsResendWithinCooldown() {
            when(emailVerificationRepository.findByEmail(EMAIL))
                    .thenReturn(Optional.of(existing(LocalDateTime.now().minusSeconds(59))));

            assertThatThrownBy(() -> authService.requestEmailVerification(emailRequest(EMAIL)))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_REQUEST_TOO_FREQUENT);

            verify(emailVerificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("61초가 지났으면 재발급을 허용한다 (경계가 60초라는 사실을 고정)")
        void allowsResendAfterCooldown() {
            when(emailVerificationRepository.findByEmail(EMAIL))
                    .thenReturn(Optional.of(existing(LocalDateTime.now().minusSeconds(61))));

            assertThatCode(() -> authService.requestEmailVerification(emailRequest(EMAIL)))
                    .doesNotThrowAnyException();

            verify(emailVerificationRepository).save(any());
        }

        @Test
        @DisplayName("updatedAt 이 null 이면(감사 이전 행) 쿨다운을 적용하지 않는다")
        void nullUpdatedAtIsNotThrottled() {
            when(emailVerificationRepository.findByEmail(EMAIL))
                    .thenReturn(Optional.of(existing(null)));

            assertThatCode(() -> authService.requestEmailVerification(emailRequest(EMAIL)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("재발급은 기존 행의 id 를 재사용한다 — 새 행이면 email UNIQUE 제약에 걸린다")
        void reusesExistingRowId() {
            EmailVerification old = existing(LocalDateTime.now().minusMinutes(5));
            org.springframework.test.util.ReflectionTestUtils.setField(old, "id", 99L);
            when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.of(old));

            authService.requestEmailVerification(emailRequest(EMAIL));

            assertThat(captureSaved().getId()).isEqualTo(99L);
        }
    }

    @Nested
    @DisplayName("코드 검증 — 실패 원인을 구분해 알려주지 않는다 (열거 방지)")
    class VerifyEmail {

        @Test
        @DisplayName("성공하면 verified 로 바꾸고 일회용 티켓을 돌려준다")
        void issuesTicket() {
            EmailVerification verification = valid("123456");
            when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.of(verification));

            String ticket = authService.verifyEmail(verifyRequest(EMAIL, "123456"));

            assertThat(ticket).isNotBlank();
            assertThat(verification.getVerified()).isTrue();
            assertThat(verification.getVerificationToken()).isEqualTo(ticket);
            assertThat(verification.getVerifiedAt()).isNotNull();
            verify(emailVerificationRepository).save(verification);
        }

        @Test
        @DisplayName("인증 요청 자체가 없던 이메일도 INVALID_VERIFICATION_CODE 다")
        void unknownEmail() {
            when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.verifyEmail(verifyRequest(EMAIL, "123456")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        @Test
        @DisplayName("만료된 코드도 같은 에러다")
        void expiredCode() {
            EmailVerification expired = EmailVerification.builder()
                    .email(EMAIL).code("123456")
                    .expiresAt(LocalDateTime.now().minusMinutes(1))
                    .build();
            when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> authService.verifyEmail(verifyRequest(EMAIL, "123456")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        @Test
        @DisplayName("이미 인증한 코드를 또 쓰면 실패한다 — isValid() 가 verified 도 본다")
        void alreadyVerifiedCodeCannotBeReused() {
            EmailVerification used = valid("123456");
            used.verify("some-ticket");
            when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.of(used));

            assertThatThrownBy(() -> authService.verifyEmail(verifyRequest(EMAIL, "123456")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        @Test
        @DisplayName("코드가 틀리면 저장하지 않는다")
        void wrongCode() {
            when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.of(valid("123456")));

            assertThatThrownBy(() -> authService.verifyEmail(verifyRequest(EMAIL, "000000")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);

            verify(emailVerificationRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("학교 이메일 인증 (소셜 로그인 유저용)")
    class SchoolEmail {

        @Test
        @DisplayName("없는 유저면 USER_NOT_FOUND")
        void unknownUser() {
            when(userRepository.existsById(USER_ID)).thenReturn(false);

            assertThatThrownBy(() -> authService.requestSchoolEmailVerification(USER_ID, schoolRequest(EMAIL)))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName(".ac.kr 이 아니면 거부한다")
        void nonSchoolDomain() {
            when(userRepository.existsById(USER_ID)).thenReturn(true);

            assertThatThrownBy(() -> authService.requestSchoolEmailVerification(USER_ID, schoolRequest("me@gmail.com")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_EMAIL_DOMAIN);
        }

        @Test
        @DisplayName("다른 계정이 이미 쓰는 학교 이메일이면 코드를 보내지 않는다")
        void alreadyUsedSchoolEmail() {
            when(userRepository.existsById(USER_ID)).thenReturn(true);
            when(userRepository.existsBySchoolEmail(EMAIL)).thenReturn(true);

            assertThatThrownBy(() -> authService.requestSchoolEmailVerification(USER_ID, schoolRequest(EMAIL)))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.SCHOOL_EMAIL_ALREADY_USED);

            verify(emailVerificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("검증 성공하면 유저가 재학생 상태로 바뀐다")
        void verifyMarksUserAsSchoolVerified() {
            User user = User.builder().id(USER_ID).email("kakao@x.com").name("김학생").build();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.existsBySchoolEmail(EMAIL)).thenReturn(false);
            when(emailVerificationRepository.findByEmail(EMAIL)).thenReturn(Optional.of(valid("123456")));

            authService.verifySchoolEmail(USER_ID, schoolVerifyRequest(EMAIL, "123456"));

            assertThat(user.isSchoolVerified()).isTrue();
            assertThat(user.getSchoolEmail()).isEqualTo(EMAIL);
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("검증 직전에 선점 여부를 다시 본다 — 코드 조회조차 하지 않는다 (경합 가드)")
        void rechecksOwnershipBeforeVerifying() {
            when(userRepository.findById(USER_ID))
                    .thenReturn(Optional.of(User.builder().id(USER_ID).name("김학생").build()));
            when(userRepository.existsBySchoolEmail(EMAIL)).thenReturn(true);

            assertThatThrownBy(() -> authService.verifySchoolEmail(USER_ID, schoolVerifyRequest(EMAIL, "123456")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.SCHOOL_EMAIL_ALREADY_USED);

            verify(emailVerificationRepository, never()).findByEmail(anyString());
            verify(userRepository, never()).save(any());
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private EmailVerification captureSaved() {
        ArgumentCaptor<EmailVerification> captor = ArgumentCaptor.forClass(EmailVerification.class);
        verify(emailVerificationRepository).save(captor.capture());
        return captor.getValue();
    }

    private EmailVerification existing(LocalDateTime updatedAt) {
        EmailVerification verification = EmailVerification.builder()
                .email(EMAIL).code("111111")
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(verification, "updatedAt", updatedAt);
        return verification;
    }

    private EmailVerification valid(String code) {
        return EmailVerification.builder()
                .email(EMAIL).code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }

    private EmailRequest emailRequest(String email) {
        EmailRequest request = new EmailRequest();
        request.setEmail(email);
        return request;
    }

    private EmailVerifyRequest verifyRequest(String email, String code) {
        EmailVerifyRequest request = new EmailVerifyRequest();
        request.setEmail(email);
        request.setCode(code);
        return request;
    }

    private SchoolEmailRequest schoolRequest(String email) {
        SchoolEmailRequest request = new SchoolEmailRequest();
        request.setSchoolEmail(email);
        return request;
    }

    private SchoolEmailVerifyRequest schoolVerifyRequest(String email, String code) {
        SchoolEmailVerifyRequest request = new SchoolEmailVerifyRequest();
        request.setSchoolEmail(email);
        request.setCode(code);
        return request;
    }
}

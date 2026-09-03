package com.example.mateon.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원가입 티켓 유효성 갈래를 고정한다.
 *
 * <p>
 * {@code isTicketValid} 는 AND 다섯 개라, 인증 성공 경로만 보면 토큰 불일치·TTL 초과·
 * 미인증이 모두 같은 false 로 뭉개진다. 프론트가 "다시 인증하세요"를 띄울 조건이라
 * 갈래를 나눠 둔다.
 */
class EmailVerificationTest {

    private static final String TOKEN = "ticket-1";

    @Test
    @DisplayName("인증 완료 직후 같은 토큰은 유효하다")
    void freshlyVerifiedTicketIsValid() {
        EmailVerification verification = pending();
        verification.verify(TOKEN);

        assertThat(verification.isTicketValid(TOKEN, Duration.ofMinutes(30))).isTrue();
    }

    @Test
    @DisplayName("아직 인증하지 않았으면 티켓이 없다")
    void unverifiedIsInvalid() {
        assertThat(pending().isTicketValid(TOKEN, Duration.ofMinutes(30))).isFalse();
    }

    @Test
    @DisplayName("토큰이 다르면 무효다")
    void mismatchedTokenIsInvalid() {
        EmailVerification verification = pending();
        verification.verify(TOKEN);

        assertThat(verification.isTicketValid("other", Duration.ofMinutes(30))).isFalse();
    }

    @Test
    @DisplayName("TTL 이 지난 티켓은 무효다")
    void expiredTtlIsInvalid() {
        EmailVerification verification = pending();
        verification.verify(TOKEN);
        verification = EmailVerification.builder()
          .email(verification.getEmail())
          .code(verification.getCode())
          .expiresAt(verification.getExpiresAt())
          .verified(true)
          .verificationToken(TOKEN)
          .verifiedAt(LocalDateTime.now().minusHours(2))
          .build();

        assertThat(verification.isTicketValid(TOKEN, Duration.ofMinutes(30))).isFalse();
    }

    @Test
    @DisplayName("인증은 됐지만 발급 시각이 없으면 무효다")
    void verifiedWithoutVerifiedAtIsInvalid() {
        EmailVerification verification = EmailVerification.builder()
          .email("a@test.ac.kr")
          .code("123456")
          .expiresAt(LocalDateTime.now().plusMinutes(10))
          .verified(true)
          .verificationToken(TOKEN)
          .verifiedAt(null)
          .build();

        assertThat(verification.isTicketValid(TOKEN, Duration.ofMinutes(30))).isFalse();
    }

    @Test
    @DisplayName("인증은 됐지만 토큰이 비어 있으면 무효다")
    void verifiedWithoutTokenIsInvalid() {
        EmailVerification verification = EmailVerification.builder()
          .email("a@test.ac.kr")
          .code("123456")
          .expiresAt(LocalDateTime.now().plusMinutes(10))
          .verified(true)
          .verificationToken(null)
          .verifiedAt(LocalDateTime.now())
          .build();

        assertThat(verification.isTicketValid(TOKEN, Duration.ofMinutes(30))).isFalse();
    }

    @Test
    @DisplayName("만료되지 않았고 미인증이면 코드 입력 단계로 유효하다")
    void pendingUnexpiredIsValidForCode() {
        assertThat(pending().isValid()).isTrue();
        assertThat(pending().isExpired()).isFalse();
    }

    private static EmailVerification pending() {
        return EmailVerification.builder()
          .email("a@test.ac.kr")
          .code("123456")
          .expiresAt(LocalDateTime.now().plusMinutes(10))
          .verified(false)
          .build();
    }
}

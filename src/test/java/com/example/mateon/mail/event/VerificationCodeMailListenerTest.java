package com.example.mateon.mail.event;

import com.example.mateon.mail.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 메일 발송 실패가 회원가입을 무너뜨리지 않게 하는 유일한 장치.
 *
 * <p>
 * 이 리스너의 {@code try-catch} 는 없어도 컴파일되고, 평소에는 아무 차이도 없다. 차이가
 * 드러나는 건 Gmail 이 흔들릴 때다 — 그 catch 가 없으면 SMTP 예외가 AFTER_COMMIT 처리 체인으로
 * 올라가고, 그러면 이미 커밋된 인증코드 행은 남았는데 요청은 에러로 끝나 사용자에게는
 * "인증코드 요청 실패" 로 보인다. 게다가 쿨다운(60초)은 이미 시작됐으므로 재시도조차 막힌다.
 *
 * <p>
 * 즉 <b>메일 서버 장애가 회원가입 장애로 번지지 않게</b> 하는 게 이 클래스의 전부다.
 */
class VerificationCodeMailListenerTest {

    private MailService mailService;
    private VerificationCodeMailListener listener;

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        listener = new VerificationCodeMailListener(mailService);
    }

    @Test
    @DisplayName("이벤트의 이메일과 코드를 그대로 발송에 넘긴다")
    void delegates() {
        listener.onVerificationCodeIssued(new VerificationCodeIssuedEvent("student@univ.ac.kr", "123456"));

        verify(mailService).sendVerificationCode("student@univ.ac.kr", "123456");
    }

    @Test
    @DisplayName("SMTP 장애를 삼킨다 — 메일 서버가 죽어도 회원가입 요청까지 실패하면 안 된다")
    void swallowsSmtpFailure() {
        doThrow(new MailSendException("연결 거부"))
          .when(mailService).sendVerificationCode(anyString(), anyString());

        assertThatCode(() -> listener.onVerificationCodeIssued(
          new VerificationCodeIssuedEvent("student@univ.ac.kr", "123456")))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("메일 조립 실패(IllegalStateException)도 마찬가지로 삼킨다")
    void swallowsBuildFailure() {
        doThrow(new IllegalStateException("인증 메일 생성에 실패했습니다."))
          .when(mailService).sendVerificationCode(anyString(), anyString());

        assertThatCode(() -> listener.onVerificationCodeIssued(
          new VerificationCodeIssuedEvent("student@univ.ac.kr", "123456")))
          .doesNotThrowAnyException();
    }
}

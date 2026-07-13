package com.example.mateon.mail.event;

import com.example.mateon.mail.service.MailService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class VerificationCodeMailListener {

    private static final Logger log = LoggerFactory.getLogger(VerificationCodeMailListener.class);

    private final MailService mailService;

    // 인증코드 저장 트랜잭션이 커밋된 뒤에만 메일을 발송한다.
    //   - 느린 SMTP 발송이 트랜잭션/DB 커넥션을 붙잡지 않도록 커밋 후로 미룬다.
    //   - 발송 실패는 무시(로그만)한다. 코드 행은 그대로 남고, 사용자는 (쿨다운 경과 후) 재요청하면 된다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationCodeIssued(VerificationCodeIssuedEvent event) {
        try {
            mailService.sendVerificationCode(event.email(), event.code());
        } catch (Exception e) {
            log.warn("인증코드 메일 발송 실패: email={}", event.email(), e);
        }
    }
}

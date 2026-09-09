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
public class PasswordResetMailListener {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailListener.class);

    private final MailService mailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetLinkIssued(PasswordResetLinkIssuedEvent event) {
        try {
            mailService.sendPasswordResetLink(event.email(), event.url());
        } catch (Exception e) {
            log.warn("비밀번호 재설정 메일 발송 실패: email={}", event.email(), e);
        }
    }
}

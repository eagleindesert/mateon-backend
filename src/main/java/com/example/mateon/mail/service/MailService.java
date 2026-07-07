package com.example.mateon.mail.service;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    public void sendVerificationCode(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[단스펙] 이메일 인증코드");
        message.setText("안녕하세요.\n\n" +
                "단스펙 회원가입을 위한 인증코드입니다.\n\n" +
                "인증코드: " + code + "\n\n" +
                "이 코드는 5분간 유효합니다.\n\n" +
                "본인이 요청한 것이 아니라면 이 메일을 무시하세요.");
        mailSender.send(message);
    }
}


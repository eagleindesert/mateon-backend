package com.example.mateon.mail.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class MailService {

    private static final String SENDER_NAME = "MateOn";

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String from;

    public void sendVerificationCode(String to, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(from, SENDER_NAME);
            helper.setReplyTo(from, SENDER_NAME);
            helper.setTo(to);
            helper.setSubject("[MateOn] 이메일 인증코드");
            // 평문 대체본 + HTML 본문(멀티파트). 평문 단독보다 스팸 점수가 낮다.
            helper.setText(buildPlainText(code), buildHtml(code));

            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new IllegalStateException("인증 메일 생성에 실패했습니다.", e);
        }
    }

    private String buildPlainText(String code) {
        return "안녕하세요.\n\n" +
                "메이트온 회원가입을 위한 인증코드입니다.\n\n" +
                "인증코드: " + code + "\n\n" +
                "이 코드는 5분간 유효합니다.\n\n" +
                "본인이 요청한 것이 아니라면 이 메일을 무시하세요.\n\n" +
                "MateOn";
    }

    private String buildHtml(String code) {
        return "<div style=\"max-width:480px;margin:0 auto;font-family:'Apple SD Gothic Neo',"
                + "'Malgun Gothic',sans-serif;color:#222;line-height:1.6;\">"
                + "<h2 style=\"margin:0 0 16px;\">이메일 인증코드</h2>"
                + "<p>안녕하세요.<br>메이트온 회원가입을 위한 인증코드입니다.</p>"
                + "<div style=\"font-size:28px;font-weight:700;letter-spacing:4px;"
                + "background:#f4f4f8;border-radius:8px;padding:16px;text-align:center;margin:20px 0;\">"
                + code + "</div>"
                + "<p style=\"color:#666;\">이 코드는 <b>5분간</b> 유효합니다.</p>"
                + "<p style=\"color:#999;font-size:13px;\">본인이 요청한 것이 아니라면 이 메일을 무시하세요.</p>"
                + "<hr style=\"border:none;border-top:1px solid #eee;margin:24px 0;\">"
                + "<p style=\"color:#aaa;font-size:12px;\">MateOn</p>"
                + "</div>";
    }
}

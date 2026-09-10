package com.example.mateon.mail.event;

/**
 * 비밀번호 재설정 링크가 발급됐음을 알린다. 메일 발송은 커밋 후 리스너가 한다.
 */
public record PasswordResetLinkIssuedEvent(String email, String url) {
}

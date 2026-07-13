package com.example.mateon.mail.event;

// 인증코드가 발급/저장되었음을 알리는 이벤트.
// 실제 메일 발송은 트랜잭션 커밋 후 리스너에서 처리한다. ([[VerificationCodeMailListener]])
public record VerificationCodeIssuedEvent(String email, String code) {
}

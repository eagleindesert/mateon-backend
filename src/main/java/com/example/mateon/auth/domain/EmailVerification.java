package com.example.mateon.auth.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "email_verifications")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String email;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean verified = false;

    // 인증 성공 시 발급되는 일회용 티켓. 코드 소유자에게만 반환되며, 회원가입 시 이 값으로
    // "인증을 완료한 주체"를 식별한다. (이메일만으로는 요청자와 인증자가 분리되어 도용 가능)
    @Column(length = 36)
    private String verificationToken;

    // 티켓 발급(인증 성공) 시각. 티켓 TTL 계산 기준.
    private LocalDateTime verifiedAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    // 코드 검증 성공: verified 로 마킹하고 일회용 티켓을 발급한다.
    public void verify(String token) {
        this.verified = true;
        this.verificationToken = token;
        this.verifiedAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !isExpired() && !verified;
    }

    // 회원가입에 사용할 티켓이 유효한지 확인한다.
    //   - 제출된 토큰이 발급된 티켓과 일치하고
    //   - 발급 후 ttl 이내여야 한다. (오래 방치된 인증 상태의 영구 선점 방지)
    public boolean isTicketValid(String token, java.time.Duration ttl) {
        return verified
                && verificationToken != null
                && verificationToken.equals(token)
                && verifiedAt != null
                && verifiedAt.isAfter(LocalDateTime.now().minus(ttl));
    }
}


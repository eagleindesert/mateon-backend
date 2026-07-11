package com.example.mateon.debug.oauth;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * [로컬 테스트 전용] 카카오 인가코드 임시 저장소.
 * 브라우저 리다이렉트로 받은 인가코드를 셸(get-kakao-token.ps1)이 docker exec psql 로
 * 읽어갈 수 있도록 DB 에 잠깐 보관한다. 실배포와 무관한 디버그 편의 테이블.
 */
@Entity
@Table(name = "oauth_debug_codes")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class OAuthDebugCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String code;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

package com.example.mateon.aichat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 대화 한 줄. 게이트웨이 턴과 도메인 턴이 같은 테이블에 시간순으로 쌓인다.
 *
 * <p><b>{@link #domain} / {@link #domainRefId} 가 이 설계의 핵심이다.</b> 게이트웨이가 아직
 * 도메인을 못 정했거나(되묻기) 범위 밖이라 직접 답한 턴은 둘 다 null 로 남는다. 라우팅이
 * 확정되는 순간 그 발화부터 도메인과 그 도메인의 세션 id 가 찍힌다.
 *
 * <p>덕분에 두 가지가 동시에 성립한다:
 * <ul>
 *   <li>화면 복원은 대화 전체를 시간순으로 읽으면 된다 — 게이트웨이 턴도 사용자 눈에는 대화다.</li>
 *   <li>도메인 AI 로 보낼 발화는 {@code domainRefId} 로 정확히 걸러진다 — 라우팅 전 잡담이
 *       FastAPI 로 새어 나가지 않고, 완료된 옛 세션의 발화가 새 세션에 섞이지도 않는다.</li>
 * </ul>
 *
 * <p>seq 는 대화 안의 정렬 키일 뿐이다. FastAPI 로 보낼 때는 USER 행만 골라 1..N 으로
 * 재채번한다 (AI 명세가 id 의 연속 증가를 요구한다).
 */
@Entity
@Table(name = "ai_conversation_messages", indexes = {
        @Index(name = "idx_ai_conv_msg_conversation", columnList = "conversation_id, seq"),
        @Index(name = "idx_ai_conv_msg_domain_ref", columnList = "domain, domain_ref_id, seq")
})
@Getter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class AiConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private AiConversation conversation;

    @Column(nullable = false)
    private Integer seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AiChatRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 이 발화의 소관 도메인. null 이면 게이트웨이가 혼자 처리한 턴이다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RoutableDomain domain;

    /** 도메인 쪽 세션 id (matching 이면 matching_intent_sessions.id). domain 과 함께 채워진다. */
    @Column(name = "domain_ref_id")
    private Long domainRefId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public AiConversationMessage(AiConversation conversation, Integer seq,
                                 AiChatRole role, String content) {
        this.conversation = conversation;
        this.seq = seq;
        this.role = role;
        this.content = content;
    }

    /**
     * 이 발화를 도메인 소관으로 표시한다. 라우팅이 확정되는 순간 게이트웨이가 호출한다.
     *
     * <p>위임할 곳이 없는 판정(UNCLEAR/OUT_OF_SCOPE)은 여기 오지 않는다 — 그런 턴은 domain 이
     * null 로 남아야 도메인 AI 로 새어 나가지 않는다.
     */
    public void assignDomain(RoutableDomain domain, Long domainRefId) {
        this.domain = domain;
        this.domainRefId = domainRefId;
    }
}

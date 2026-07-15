package com.example.mateon.matching.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 대화 이력 한 턴. USER 발화와 AI 의 assistant_message 를 둘 다 여기에 쌓는다.
 *
 * ※ 저장과 전송은 다르다:
 *   - 저장: USER + ASSISTANT 둘 다 (프론트 대화 복원·표시용)
 *   - FastAPI 전송: USER 만 (명세상 messages 는 "사용자가 한 말"만 담는다)
 *
 * seq 는 정렬 키일 뿐이다. AI 로 보낼 때는 USER 행만 골라 1..N 으로 재채번한다.
 */
@Entity
@Table(name = "matching_intent_messages", indexes = {
        @Index(name = "idx_matching_intent_messages_session", columnList = "session_id, seq")
})
@Getter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class MatchingIntentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private MatchingIntentSession session;

    @Column(nullable = false)
    private Integer seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IntentMessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public MatchingIntentMessage(MatchingIntentSession session, Integer seq,
                                 IntentMessageRole role, String message) {
        this.session = session;
        this.seq = seq;
        this.role = role;
        this.message = message;
    }
}

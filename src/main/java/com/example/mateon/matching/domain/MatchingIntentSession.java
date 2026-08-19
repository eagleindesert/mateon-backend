package com.example.mateon.matching.domain;

import com.example.mateon.aichat.domain.AiConversation;
import com.example.mateon.matching.converter.StringListConverter;
import com.example.mateon.user.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 의도 추출 대화 세션. <b>도메인 상태만</b> 담는다 — 진행 상황, 마지막 추출 결과, 완료·만료 판정.
 *
 * 대화 이력 자체는 여기 없다. AI 대화 통합 로그(ai_conversation_messages)가 갖고 있고, 이 세션은
 * {@link #conversation} 으로 그 스레드를 가리킨다. 게이트웨이가 위임할 때 자기도 쓰고 여기서도
 * 쓰면 같은 발화가 두 벌 남기 때문에, 기록은 통합 로그 한 곳으로 모았다 (V30).
 *
 * 사용자당 IN_PROGRESS 는 최대 1개 — V7 의 부분 유니크 인덱스
 * (uk_matching_intent_sessions_active)가 DB 레벨에서 보장한다.
 * Hibernate validate 는 부분 인덱스를 검사하지 않으므로 @Table(indexes=) 에 선언하지 않는다.
 */
@Entity
@Table(name = "matching_intent_sessions", indexes = {
        @Index(name = "idx_matching_intent_sessions_user", columnList = "user_id, id")
})
@Getter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class MatchingIntentSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 이 세션의 발화가 쌓이는 통합 로그 스레드. NOT NULL 이다 — 비어 있으면 대화를 읽을 수 없어
     * 앱이 조용히 깨지므로, V30 이 기존 행까지 채운 뒤 제약을 걸었다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private AiConversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IntentSessionStatus status;

    /** 마지막 AI 응답의 missing_fields (CSV). 완료 시 비어있다. */
    @Convert(converter = StringListConverter.class)
    @Column(name = "last_missing_fields", columnDefinition = "TEXT")
    private List<String> lastMissingFields;

    /** 마지막 AI 응답의 extracted 원본 JSON. GET /session 복원용. */
    @Column(name = "last_extracted_json", columnDefinition = "TEXT")
    private String lastExtractedJson;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 메시지/AI응답마다 갱신 → 지연 만료 판정 기준. */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public MatchingIntentSession(User user, AiConversation conversation) {
        this.user = user;
        this.conversation = conversation;
        this.status = IntentSessionStatus.IN_PROGRESS;
    }

    /** AI 응답을 반영한다. 완료(missing_fields 가 빔)면 COMPLETED 로 전이한다. */
    public void applyAiResult(List<String> missingFields, String extractedJson, boolean completed) {
        this.lastMissingFields = missingFields;
        this.lastExtractedJson = extractedJson;
        if (completed) {
            this.status = IntentSessionStatus.COMPLETED;
            this.completedAt = LocalDateTime.now();
        }
    }

    public void abandon() {
        this.status = IntentSessionStatus.ABANDONED;
    }

    public void expire() {
        this.status = IntentSessionStatus.EXPIRED;
    }

    public boolean isInProgress() {
        return this.status == IntentSessionStatus.IN_PROGRESS;
    }

    /** updatedAt 이 threshold 보다 오래됐으면 방치된 세션이다. */
    public boolean isStaleAt(LocalDateTime threshold) {
        return this.updatedAt != null && this.updatedAt.isBefore(threshold);
    }
}

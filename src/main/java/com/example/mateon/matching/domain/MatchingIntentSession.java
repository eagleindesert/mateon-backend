package com.example.mateon.matching.domain;

import com.example.mateon.aichat.domain.AiDomainTask;
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
 * 의도 추출 대화의 <b>도메인 상태만</b> 담는다 — 마지막 추출 결과와 완료 시각.
 *
 * <p>
 * <b>수명 상태는 여기 없다.</b> 진행 중인지, 왜 끝났는지, 방치됐는지는 상위의
 * {@link AiDomainTask} 가 갖는다. 예전에는 이 테이블이 status(IN_PROGRESS/COMPLETED/ABANDONED/
 * EXPIRED)를 들었는데, 그 네 값은 전부 "살아 있나 / 왜 끝났나"라서 매칭 고유의 사실이 하나도
 * 없었다. 상위와 양쪽에 두면 "이 작업이 살아 있나"의 답이 두 곳에 생기고, 한 번 어긋나면
 * 게이트웨이는 진행 중으로 보고 통과시키는데 여기서는 없다고 보고 새 작업을 연다. 예외도 안 나고
 * 테스트도 안 깨지는 종류라 V31 에서 위로 올렸다.
 *
 * <p>
 * ※ 이름 주의 — 여기서 "session" 은 <b>도메인 작업 한 판</b>이지 대화 세션이 아니다.
 * 사용자가 사이드바에서 고르는 그것은 {@code AiChatSession} 이다. 이름을 못 바꾸는 건
 * {@code GET /api/matching/intents/session} 과 응답의 {@code sessionId} 가 프론트 계약이라서다.
 *
 * <p>
 * 대화 이력도 여기 없다. AI 채팅 통합 로그(ai_chat_messages)가 갖고 있고 {@link #task} 로
 * 이어진다. 게이트웨이가 위임할 때 자기도 쓰고 여기서도 쓰면 같은 발화가 두 벌 남기 때문에,
 * 기록은 통합 로그 한 곳으로 모았다 (V30).
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
     * 이 매칭 작업의 수명과 대화를 쥐고 있는 상위 작업. 1:1 이다 — 작업은 domain 이 하나로
     * 정해져 있으니 부속 행도 하나뿐이라, DB 에 UNIQUE 를 걸어 둔다.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false, unique = true)
    private AiDomainTask task;

    /**
     * 마지막 AI 응답의 missing_fields (CSV). 완료 시 비어있다.
     */
    @Convert(converter = StringListConverter.class)
    @Column(name = "last_missing_fields", columnDefinition = "TEXT")
    private List<String> lastMissingFields;

    /**
     * 마지막 AI 응답의 extracted 원본 JSON. GET /session 복원용.
     */
    @Column(name = "last_extracted_json", columnDefinition = "TEXT")
    private String lastExtractedJson;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 슬롯이 다 채워진 시각. 도메인 사실로 남겨 둘 뿐 <b>수명 판정에는 쓰이지 않는다</b> —
     * "언제 끝났나"의 정본은 {@code AiDomainTask.closedAt} 이다.
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public MatchingIntentSession(User user, AiDomainTask task) {
        this.user = user;
        this.task = task;
    }

    /**
     * AI 응답을 반영한다.
     *
     * <p>
     * 완료 전이라도 상태를 바꾸지 않는다 — 작업을 닫는 건 상위(AiDomainTaskService)의 일이다.
     * 여기서도 닫으면 종료 판정이 두 곳에서 나오게 된다.
     */
    public void applyAiResult(List<String> missingFields, String extractedJson, boolean completed) {
        this.lastMissingFields = missingFields;
        this.lastExtractedJson = extractedJson;
        if (completed) {
            this.completedAt = LocalDateTime.now();
        }
    }
}

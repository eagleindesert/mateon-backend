package com.example.mateon.aichat.domain;

import com.example.mateon.user.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * AI 대화 스레드 하나. 게이트웨이 발화와 각 도메인 발화가 여기 한 줄기로 모인다.
 *
 * <p><b>도메인 상태는 여기 없다.</b> 진행 상황·추출 결과·만료 판정 같은 건 도메인마다 형태가
 * 달라서 각자의 테이블이 갖는다(matching 이면 MatchingIntentSession). 여기는 "누가 언제 무슨
 * 말을 했는가"만 담는다 — 그래서 도메인이 늘어도 이 테이블의 컬럼은 변하지 않는다.
 *
 * <p>사용자당 ACTIVE 는 최대 1개 — V30 의 부분 유니크 인덱스(uk_ai_conversations_active)가
 * DB 레벨에서 보장한다. Hibernate validate 는 부분 인덱스를 검사하지 않으므로
 * {@code @Table(indexes=)} 에 선언하지 않는다 (MatchingIntentSession 과 같은 이유).
 */
@Entity
@Table(name = "ai_conversations", indexes = {
        @Index(name = "idx_ai_conversations_user", columnList = "user_id, id")
})
@Getter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class AiConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiConversationStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public AiConversation(User user) {
        this.user = user;
        this.status = AiConversationStatus.ACTIVE;
    }

    /** 대화를 끝낸다. 다음 발화는 새 대화를 만든다. */
    public void close() {
        this.status = AiConversationStatus.CLOSED;
    }
}

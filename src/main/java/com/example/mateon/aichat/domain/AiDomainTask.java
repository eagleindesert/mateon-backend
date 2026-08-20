package com.example.mateon.aichat.domain;

import com.example.mateon.user.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 한 스레드 안에서 도는 도메인 작업 한 판. 매칭 의도 추출 한 사이클이 여기 하나에 해당한다.
 *
 * <p>
 * <b>수명 상태는 이 테이블만 갖는다.</b> 도메인 테이블이 자기 status 를 따로 들면 "이 작업이
 * 살아 있나"의 답이 두 곳에 생기고, 한 번 어긋나면 게이트웨이는 진행 중으로 보고 통과시키는데
 * 도메인은 없다고 보고 새 작업을 연다. 예외도 안 나고 테스트도 안 깨지는 종류라 한 곳으로 모았다.
 * 도메인 테이블에는 그 도메인만 쓰는 값(매칭이면 추출 결과)만 남는다.
 *
 * <p>
 * 덕분에 게이트웨이가 도메인을 몰라도 된다 — "이 스레드에 살아 있는 작업이 있나"를 도메인
 * 서비스를 N 번 부르지 않고 여기 한 번의 조회로 답할 수 있다. 그게 이 층을 만든 이유다.
 *
 * <p>
 * <b>{@code AuditingEntityListener} 를 일부러 쓰지 않는다.</b> {@code @LastModifiedDate} 는
 * 엔티티가 더러울 때만 뜨는데, 이 작업에 메시지가 붙는다고 이 행이 더러워지지는 않는다. 그러면
 * {@code updatedAt} 이 생성 시각에 멈춰 지연 만료 판정이 통째로 거짓말이 된다 (V30 에서
 * ai_conversations 에 실제로 일어났던 일이다). 그래서 {@link #touch()} 가 직접 쓴다.
 */
@Entity
@Table(name = "ai_domain_tasks", indexes = {
    @Index(name = "idx_ai_domain_tasks_session", columnList = "chat_session_id, id")
})
@Getter
@NoArgsConstructor
public class AiDomainTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_session_id", nullable = false)
    private AiChatSession chatSession;

    /**
     * chatSession 을 거치면 알 수 있지만 비정규화한다 — "사용자당 도메인당 진행 중 1건"을
     * 보장하는 부분 유니크 인덱스(uk_ai_domain_tasks_active)가 테이블을 건널 수 없다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoutableDomain domain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiDomainTaskStatus status;

    /**
     * CLOSED 일 때만 채워진다. DB CHECK 가 status 와의 짝을 강제한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "closed_reason", length = 20)
    private TaskCloseReason closedReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 마지막 활동 시각 = 지연 만료 판정 기준. {@link #touch()} 로만 움직인다.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    public AiDomainTask(AiChatSession chatSession, User user, RoutableDomain domain) {
        LocalDateTime now = LocalDateTime.now();
        this.chatSession = chatSession;
        this.user = user;
        this.domain = domain;
        this.status = AiDomainTaskStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 이 작업에 활동이 있었음을 기록한다. 만료 판정이 이 값만 본다.
     */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 작업을 끝낸다. 사유와 시각이 함께 채워진다 — 한쪽만 채우면 DB CHECK 에 걸린다.
     *
     * <p>
     * 이미 닫힌 작업을 다시 닫지 않는다. 완료 직후 재시작 같은 경로에서 종료 사유가
     * 덮어써지면 "왜 끝났는지"가 뒤바뀐다.
     */
    public void close(TaskCloseReason reason) {
        if (this.status == AiDomainTaskStatus.CLOSED) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        this.status = AiDomainTaskStatus.CLOSED;
        this.closedReason = reason;
        this.closedAt = now;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return this.status == AiDomainTaskStatus.ACTIVE;
    }

    /**
     * updatedAt 이 threshold 보다 오래됐으면 방치된 작업이다.
     */
    public boolean isStaleAt(LocalDateTime threshold) {
        return this.updatedAt != null && this.updatedAt.isBefore(threshold);
    }

    /**
     * 이 작업이 주어진 스레드 소속인가. 스레드를 옮겨 다니며 이어지면 안 된다.
     */
    public boolean belongsTo(Long chatSessionId) {
        return this.chatSession != null && this.chatSession.getId().equals(chatSessionId);
    }
}

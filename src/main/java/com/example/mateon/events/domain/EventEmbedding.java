package com.example.mateon.events.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "event_embeddings")
@Getter
@Setter
public class EventEmbedding {

    @Id
    @Column(name = "event_id")
    private Long eventId;

    /**
     * 갱신에 한 번도 성공하지 못했으면 null 이다. 따라서 "행이 있다"가 "임베딩이 있다"를
     * 뜻하지 않는다. 조회 측은 반드시 null 을 걸러야 한다.
     */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "embedding", columnDefinition = "vector(1536)")
    private float[] embedding;

    @Column(name = "model", nullable = false, length = 50)
    private String model = "text-embedding-3-small";

    @Enumerated(EnumType.STRING)
    @Column(name = "refresh_status", nullable = false, length = 20)
    private EventEmbeddingRefreshStatus refreshStatus = EventEmbeddingRefreshStatus.SUCCESS;

    /**
     * 성공/실패 무관하게 마지막으로 갱신을 시도한 시각.
     */
    @Column(name = "last_attempted_at")
    private LocalDateTime lastAttemptedAt;

    /**
     * 연속 실패 횟수. 성공하면 0 으로 리셋된다.
     */
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    /**
     * 마지막 실패 사유 (예외 클래스명 + 메시지, 500자 truncate). 성공하면 null.
     */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /**
     * 이 행의 임베딩이 반영하는 활동 데이터의 시점 (= 계산에 사용한 {@code Event.updatedAt}).
     * 도착한 결과가 이 값보다 낡았으면 저장하지 않는다.
     *
     * <p>
     * 실패 기록은 이 값을 올리지 않는다 — 내용은 여전히 예전 시점 것이기 때문이다.
     * NULL 은 "판정 불가"(첫 갱신)라 아무것도 버리지 않는다.
     */
    @Column(name = "source_updated_at")
    private LocalDateTime sourceUpdatedAt;

    /**
     * 낙관적 락 버전. 위 판정과 저장 사이의 좁은 창까지 닫는다.
     *
     * <p>
     * 래퍼 타입인 이유: Spring Data 가 이 필드의 null 여부로 신규/기존을 판별해, 신규 행에
     * 불필요한 select 없이 곧장 insert 한다.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

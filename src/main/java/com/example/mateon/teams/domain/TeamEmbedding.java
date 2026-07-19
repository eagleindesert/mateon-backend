package com.example.mateon.teams.domain;

import com.example.mateon.teams.converter.RoleListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "team_embeddings")
@Getter
@Setter
public class TeamEmbedding {

    @Id
    @Column(name = "team_id")
    private Long teamId;

    /**
     * 갱신에 한 번도 성공하지 못했으면 null 이다 (V10 에서 NOT NULL 해제 — 실패도 기록해야 하므로).
     * 따라서 "행이 있다"가 "임베딩이 있다"를 뜻하지 않는다. 조회 측은 반드시 null 을 걸러야 한다.
     */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "embedding", columnDefinition = "vector(1536)")
    private float[] embedding;

    @Column(name = "model", nullable = false, length = 50)
    private String model = "text-embedding-3-small";

    // ── AI 서버(embedding:refresh) 응답 메타데이터 (V8) ─────────────────────
    // 전부 nullable: intro_text 에서 추출 못 한 항목(missing_fields)은 null 로 남는다.

    /** 임베딩 계산에 실제 사용된 원문 */
    @Column(name = "embedding_text", columnDefinition = "text")
    private String embeddingText;

    @Convert(converter = RoleListConverter.class)
    @Column(name = "recruiting_roles", columnDefinition = "text")
    private List<String> recruitingRoles;

    @Convert(converter = RoleListConverter.class)
    @Column(name = "required_skills", columnDefinition = "text")
    private List<String> requiredSkills;

    @Column(name = "activity_goal", columnDefinition = "text")
    private String activityGoal;

    @Column(name = "activity_style", columnDefinition = "text")
    private String activityStyle;

    @Column(name = "activity_intensity", columnDefinition = "text")
    private String activityIntensity;

    @Column(name = "beginner_friendly")
    private Boolean beginnerFriendly;

    /** AI 가 intro_text 에서 추출을 시도했지만 못 채운 항목 */
    @Convert(converter = RoleListConverter.class)
    @Column(name = "missing_fields", columnDefinition = "text")
    private List<String> missingFields;

    // ── 갱신 시도 상태 (V10) ────────────────────────────────────────────────
    // 비동기 갱신은 실패해도 warn 만 남기고 끝나 추적이 불가능했다. 시도의 성패를 여기 남긴다.

    @Enumerated(EnumType.STRING)
    @Column(name = "refresh_status", nullable = false, length = 20)
    private TeamEmbeddingRefreshStatus refreshStatus = TeamEmbeddingRefreshStatus.SUCCESS;

    /** 성공/실패 무관하게 마지막으로 갱신을 시도한 시각. */
    @Column(name = "last_attempted_at")
    private LocalDateTime lastAttemptedAt;

    /** 연속 실패 횟수. 성공하면 0 으로 리셋된다 (향후 백오프 근거). */
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    /** 마지막 실패 사유 (예외 클래스명 + 메시지, 500자 truncate). 성공하면 null. */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

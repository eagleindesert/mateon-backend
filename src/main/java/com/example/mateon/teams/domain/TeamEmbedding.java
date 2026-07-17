package com.example.mateon.teams.domain;

import com.example.mateon.teams.converter.RoleListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
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

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "embedding", columnDefinition = "vector(1536)", nullable = false)
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

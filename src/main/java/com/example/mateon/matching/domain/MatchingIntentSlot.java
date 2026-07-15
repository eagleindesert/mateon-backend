package com.example.mateon.matching.domain;

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
 * FastAPI 가 추출해 준 매칭 의도 슬롯. 사용자당 1건 (upsert).
 *
 * 사용자당 1건인 이유: user_embeddings 의 PK 가 user_id 라 임베딩이 구조적으로 사용자당
 * 1개다. 슬롯 1 : 벡터 1 로 맞춰야 "어느 슬롯이 이 벡터를 만들었나"가 명확하다.
 *
 * ※ embedding_vector 는 여기가 아니라 user_embeddings (V6) 에 저장된다.
 *   여기 있는 embeddingText 는 벡터가 아니라 "무엇을 임베딩했는지"의 원문이다.
 *
 * ※ desiredRoles("BE") / experienceLevel("beginner") 은 AI 의 정규화 코드지만 enum 으로
 *   만들지 않는다. 외부 서버는 신뢰할 수 없는 입력원이라, enum 이면 스펙 밖 값 하나에 500 이
 *   난다. 기존 Team.role(한글 자유문자열)과의 매핑은 추천 단계에서 결정한다.
 */
@Entity
@Table(name = "matching_intent_slots")
@Getter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class MatchingIntentSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 이 슬롯을 만들어낸 세션. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private MatchingIntentSession session;

    @Convert(converter = StringListConverter.class)
    @Column(name = "desired_roles", columnDefinition = "TEXT")
    private List<String> desiredRoles;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> skills;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> interests;

    @Column(name = "activity_goal", length = 255)
    private String activityGoal;

    @Column(name = "activity_style", length = 255)
    private String activityStyle;

    @Column(name = "experience_level", length = 50)
    private String experienceLevel;

    /** 임베딩의 원문 (벡터 아님). */
    @Column(name = "embedding_text", columnDefinition = "TEXT")
    private String embeddingText;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public MatchingIntentSlot(User user) {
        this.user = user;
    }

    /** AI 추출 결과로 내용을 덮어쓴다 (사용자당 1건 upsert). */
    public void update(MatchingIntentSession session,
                       List<String> desiredRoles, List<String> skills, List<String> interests,
                       String activityGoal, String activityStyle, String experienceLevel,
                       String embeddingText) {
        this.session = session;
        this.desiredRoles = desiredRoles;
        this.skills = skills;
        this.interests = interests;
        this.activityGoal = activityGoal;
        this.activityStyle = activityStyle;
        this.experienceLevel = experienceLevel;
        this.embeddingText = embeddingText;
    }
}

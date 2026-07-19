package com.example.mateon.matching.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 추천 호출 1건에서 나온 팀 하나의 결과.
 *
 * <p>teamId 가 연관관계(@ManyToOne Team)가 아니라 raw Long 인 이유: 이건 시점 기록이라 팀이
 * 나중에 삭제돼도 남아야 한다. FK 도 걸지 않는다 (V9 마이그레이션 주석 참고).
 */
@Entity
@Table(name = "user_to_team_recommendation_items")
@Getter
@NoArgsConstructor
public class UserToTeamRecommendationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "log_id", nullable = false)
    private UserToTeamRecommendationLog log;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** 1 부터. 점수 내림차순 순위. */
    @Column(name = "rank_no", nullable = false)
    private int rankNo;

    @Column(name = "score", nullable = false)
    private double score;

    /** AI 가 만든 추천 근거 문구. 백엔드는 해석하지 않는다. */
    @Column(name = "label", columnDefinition = "text")
    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    UserToTeamRecommendationItem(UserToTeamRecommendationLog log, Long teamId,
                                 int rankNo, double score, String label) {
        this.log = log;
        this.teamId = teamId;
        this.rankNo = rankNo;
        this.score = score;
        this.label = label;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

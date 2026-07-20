package com.example.mateon.matching.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 역제안 추천 호출 1건에서 나온 유저 하나의 결과.
 *
 * <p>userId 가 연관관계(@ManyToOne User)가 아니라 raw Long 인 이유는
 * {@link UserToTeamRecommendationItem} 과 같다 — 시점 기록이라 유저가 탈퇴해도 남아야 한다.
 */
@Entity
@Table(name = "team_to_user_recommendation_items")
@Getter
@NoArgsConstructor
public class TeamToUserRecommendationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "log_id", nullable = false)
    private TeamToUserRecommendationLog log;

    /** 추천된 유저 (후보 쪽). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

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

    TeamToUserRecommendationItem(TeamToUserRecommendationLog log, Long userId,
                                 int rankNo, double score, String label) {
        this.log = log;
        this.userId = userId;
        this.rankNo = rankNo;
        this.score = score;
        this.label = label;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

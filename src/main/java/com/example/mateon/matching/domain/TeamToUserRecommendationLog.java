package com.example.mateon.matching.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 팀→유저 추천(역제안) 호출 1건의 기록 (헤더). {@link UserToTeamRecommendationLog} 의 쌍둥이다.
 *
 * <p>여기엔 역할이 하나 더 있다: 팀장이 제안을 보낼 때 team_offers 의 ai_score/ai_label 을
 * 이 로그의 아이템에서 찾아 복사한다. 덕분에 프론트가 점수를 되보낼 필요가 없고, 되보낸 값을
 * 신뢰할 필요도 없다.
 */
@Entity
@Table(name = "team_to_user_recommendation_logs")
@Getter
@NoArgsConstructor
public class TeamToUserRecommendationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 추천을 요청한 팀 (질의 쪽). */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** 요청 시점의 팀장. 팀장이 바뀌어도 누가 눌렀는지 남는다. */
    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId;

    /** AI 에 실제로 보낸 후보 수. 응답 건수와 다를 수 있어 따로 남긴다. */
    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "log", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TeamToUserRecommendationItem> items = new ArrayList<>();

    public TeamToUserRecommendationLog(Long teamId, Long requestedByUserId, int candidateCount) {
        this.teamId = teamId;
        this.requestedByUserId = requestedByUserId;
        this.candidateCount = candidateCount;
    }

    /** 점수 내림차순으로 이미 정렬된 결과를 1부터 순위를 매겨 담는다. */
    public void addItem(Long userId, int rankNo, double score, String label) {
        this.items.add(new TeamToUserRecommendationItem(this, userId, rankNo, score, label));
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

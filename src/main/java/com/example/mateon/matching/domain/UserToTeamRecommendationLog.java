package com.example.mateon.matching.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 유저→팀 추천 호출 1건의 기록 (헤더).
 *
 * <p>AI 서버는 stateless 라 추천 이력이 남지 않는다. "언제 누구에게 무엇을 왜 추천했는지"는
 * 여기서만 알 수 있고, 나중에 추천 품질을 따질 때(어떤 label 이 실제 지원으로 이어졌나) 쓴다.
 *
 * <p>기록 실패가 추천 응답을 막지는 않는다 — 저장은 AI 호출이 끝난 뒤 별도 트랜잭션에서 한다.
 */
@Entity
@Table(name = "user_to_team_recommendation_logs")
@Getter
@NoArgsConstructor
public class UserToTeamRecommendationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** eventId 로 좁혀 추천한 경우에만. 전역 추천이면 null. */
    @Column(name = "event_id")
    private Long eventId;

    /** AI 에 실제로 보낸 후보 수. 응답 건수와 다를 수 있어 따로 남긴다. */
    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 헤더 저장 한 번으로 아이템까지 함께 넣는다 (아이템만 따로 살아남을 이유가 없다).
    @OneToMany(mappedBy = "log", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UserToTeamRecommendationItem> items = new ArrayList<>();

    public UserToTeamRecommendationLog(Long userId, Long eventId, int candidateCount) {
        this.userId = userId;
        this.eventId = eventId;
        this.candidateCount = candidateCount;
    }

    /** 점수 내림차순으로 이미 정렬된 결과를 1부터 순위를 매겨 담는다. */
    public void addItem(Long teamId, int rankNo, double score, String label) {
        this.items.add(new UserToTeamRecommendationItem(this, teamId, rankNo, score, label));
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

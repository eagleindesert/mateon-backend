package com.example.mateon.matching.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 추천 호출 1건에서 나온 팀 하나의 결과.
 *
 * <p>
 * teamId 가 연관관계(@ManyToOne Team)가 아니라 raw Long 인 이유: 이건 시점 기록이라 팀이
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

    /**
     * 1 부터. 점수 내림차순 순위.
     */
    @Column(name = "rank_no", nullable = false)
    private int rankNo;

    @Column(name = "score", nullable = false)
    private double score;

    /**
     * AI 가 만든 추천 근거 문구. 백엔드는 해석하지 않는다.
     */
    @Column(name = "label", columnDefinition = "text")
    private String label;

    /**
     * 추천 시점의 컴포넌트별 점수 (AI 응답의 component_scores). <b>원문 JSON 문자열 그대로</b>다.
     *
     * <p>
     * 파싱해서 담지 않는 이유는 명세가 "값을 재계산하거나 이름을 바꾸지 않고 선택 시점까지
     * 보관"을 요구하기 때문이다. 선택 이벤트로 되보낼 때도 이 문자열을 그대로 본문에 박으므로
     * 키 이름·순서·값이 왕복 과정에서 한 글자도 바뀌지 않는다.
     *
     * <p>
     * AI 가 component_scores 를 안 준 응답이면 null 이다 (추천 자체는 유효하다).
     */
    @Column(name = "component_scores", columnDefinition = "text")
    private String componentScores;

    /**
     * 이 후보를 실제로 골라 지원을 보낸 시각. null 이면 <b>목록에 뜨기만 했다</b>는 뜻이다.
     *
     * <p>
     * AI 로 보내는 값은 아니다 — 명세의 shown_candidates 에는 이 필드가 없고 선택된 후보는
     * 최상위 selected_candidate_id 로 식별된다. 우리 쪽 분석용이자, 선택 이벤트 전송이
     * 실패했을 때 "무엇이 선택됐었는지"가 남는 자리다.
     */
    @Column(name = "selected_at")
    private LocalDateTime selectedAt;

    /**
     * AI 가 만든 추천 상세 이유 (POST /recommendations/reason). 사용자가 카드를 선택한 시점에
     * 채워지는 lazy 값이라, null 은 "이유가 없다"가 아니라 <b>"아직 만든 적 없다"</b>는 뜻이다.
     *
     * <p>
     * 추천 당시에 함께 저장되지 않는 유일한 필드다 — 목록에 뜬 모든 후보의 이유를 미리
     * 생성하면 LLM 호출이 후보 수만큼 나간다.
     */
    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    UserToTeamRecommendationItem(UserToTeamRecommendationLog log, Long teamId,
      int rankNo, double score, String label, String componentScores) {
        this.log = log;
        this.teamId = teamId;
        this.rankNo = rankNo;
        this.score = score;
        this.label = label;
        this.componentScores = componentScores;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

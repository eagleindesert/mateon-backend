package com.example.mateon.user.domain;

import com.example.mateon.teams.service.CollaborationTemperatureCalculator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 유저별 협업 온도 집계 캐시. users 와 1:1 이지만 테이블을 분리했다 —
 * users 는 인증 핫패스에서 매번 읽히고 평판은 쓰기 패턴이 전혀 다르다 (user_embeddings 와 같은 전례).
 *
 * <p>reviewCount/ratingSum 을 함께 들고 있어서 평가 1건 추가가 O(1) 증분 갱신이 된다.
 * team_reviews 를 통째로 다시 세는 건 공식 계수를 바꿀 때뿐이다.
 */
@Entity
@Table(name = "user_collaboration_scores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCollaborationScore {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "rating_sum", nullable = false)
    private int ratingSum;

    /** 표본이 부족하면(MIN_REVIEWS 미만) null — 비공개. */
    @Column(precision = 4, scale = 1)
    private BigDecimal temperature;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static UserCollaborationScore init(Long userId) {
        UserCollaborationScore score = new UserCollaborationScore();
        score.userId = userId;
        score.reviewCount = 0;
        score.ratingSum = 0;
        score.temperature = null;
        score.updatedAt = LocalDateTime.now();
        return score;
    }

    /** 평가 1건 반영. 온도는 여기서만 갱신되므로 집계와 온도가 어긋날 수 없다. */
    public void addRating(int rating) {
        this.reviewCount += 1;
        this.ratingSum += rating;
        this.temperature = CollaborationTemperatureCalculator.temperature(reviewCount, ratingSum);
        this.updatedAt = LocalDateTime.now();
    }
}

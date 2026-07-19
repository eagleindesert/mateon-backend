package com.example.mateon.teams.domain;

import com.example.mateon.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 프로젝트 종료 후 팀원이 다른 팀원에게 남기는 평가. 1~5 점 단일 척도
 * ("이 팀원과 다시 협업하고 싶은가").
 *
 * <p><b>완전 익명이다.</b> reviewer 를 저장하는 건 오직 중복 제출 차단과 자격 검증 때문이며,
 * 어떤 응답 DTO 에도 실려서는 안 된다. 이 엔티티를 읽는 코드를 추가할 때 반드시 지킬 것.
 *
 * <p>제출 후 수정/삭제는 없다. 팀당 (평가자, 대상) 쌍당 1건이며 DB UNIQUE 제약이 이를 강제한다.
 */
@Entity
@Table(name = "team_reviews")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewee_id", nullable = false)
    private User reviewee;

    @Column(nullable = false)
    private Short rating;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public static final short MIN_RATING = 1;
    public static final short MAX_RATING = 5;

    public static boolean isValidRating(Integer rating) {
        return rating != null && rating >= MIN_RATING && rating <= MAX_RATING;
    }
}

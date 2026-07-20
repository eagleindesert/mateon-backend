package com.example.mateon.teams.domain;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 팀장이 유저에게 보낸 역제안 1건.
 *
 * <p>{@link TeamApplication} 과 방향이 반대다 — 저쪽은 유저가 요청하고 팀장이 승인하지만,
 * 이쪽은 팀장이 요청하고 유저가 승인한다. 수락되면 팀장의 재승인 없이 곧바로
 * {@link TeamMember} 가 생긴다 (팀장이 이미 고른 사람이므로).
 *
 * <p>상태 전이는 전부 이 안의 메서드로만 한다. setStatus 를 열어 두면 "PENDING 인지 확인"이
 * 호출부마다 흩어지고, 한 곳만 빠뜨려도 이미 거절한 제안을 다시 수락할 수 있게 된다.
 */
@Entity
@Table(name = "team_offers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class TeamOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    /** 제안을 받은 사람. 컬럼은 team_applications 와 같은 자리(user_id)다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User targetUser;

    @Column(columnDefinition = "TEXT")
    private String message;

    /**
     * 제안 시점의 AI 추천 점수/근거 스냅샷. 추천을 거치지 않고 보낸 제안이면 둘 다 null.
     * 프론트가 보낸 값이 아니라 team_to_user_recommendation_items 에서 서버가 찾아 넣는다.
     */
    @Column(name = "ai_score")
    private Double aiScore;

    @Column(name = "ai_label", columnDefinition = "TEXT")
    private String aiLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OfferStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 유저가 응답했거나 팀장이 취소한 시각. PENDING 이면 null. */
    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    public TeamOffer(Team team, User targetUser, String message, Double aiScore, String aiLabel) {
        this.team = team;
        this.targetUser = targetUser;
        this.message = message;
        this.aiScore = aiScore;
        this.aiLabel = aiLabel;
        this.status = OfferStatus.PENDING;
    }

    public boolean isPending() {
        return status == OfferStatus.PENDING;
    }

    /** 유저 수락. 팀원 생성은 호출자(TeamOfferService)가 같은 트랜잭션에서 함께 한다. */
    public void accept() {
        transitionFromPending(OfferStatus.ACCEPTED);
    }

    /** 유저 거절. */
    public void reject() {
        transitionFromPending(OfferStatus.REJECTED);
    }

    /** 팀장 회수. */
    public void cancel() {
        transitionFromPending(OfferStatus.CANCELED);
    }

    private void transitionFromPending(OfferStatus next) {
        if (!isPending()) {
            throw new MateonException(ErrorCode.OFFER_ALREADY_RESPONDED);
        }
        this.status = next;
        this.respondedAt = LocalDateTime.now();
    }
}

package com.example.mateon.teams.domain;

import com.example.mateon.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 팀의 현재 소속. 리더와 팀원을 role 하나로 동질화한다.
 *
 * <p>TeamApplication 과 역할이 다르다 — 저쪽은 '지원 이력', 이쪽은 '소속'. 지원서가 승인되면
 * 두 곳이 같은 트랜잭션에서 함께 바뀐다(TeamService.processApplication).
 *
 * <p>탈퇴는 행 삭제가 아니라 leftAt 기록이다. "평가 시점에 이 팀에 있었는가"를 나중에도
 * 답할 수 있어야 하기 때문이다(협업 온도).
 */
@Entity
@Table(name = "team_members")
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TeamMemberRole role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    /** NULL 이면 활성 멤버. */
    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) {
            joinedAt = LocalDateTime.now();
        }
    }

    public static TeamMember of(Team team, User user, TeamMemberRole role) {
        return TeamMember.builder().team(team).user(user).role(role).build();
    }

    public boolean isActive() {
        return leftAt == null;
    }
}

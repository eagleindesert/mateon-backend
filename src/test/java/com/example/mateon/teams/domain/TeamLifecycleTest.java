package com.example.mateon.teams.domain;

import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 팀 종료/평가 창구와 멤버십 콜백처럼 서비스 테스트가 안 밟는 전이를 고정한다.
 */
class TeamLifecycleTest {

    @Test
    @DisplayName("종료되지 않은 팀은 평가할 수 없고 마감일도 없다")
    void notEndedIsNotReviewable() {
        Team team = new Team();
        LocalDateTime now = LocalDateTime.now();

        assertThat(team.isEnded()).isFalse();
        assertThat(team.isReviewableAt(now, 14)).isFalse();
        assertThat(team.reviewDeadline(14)).isNull();
    }

    @Test
    @DisplayName("종료 직후면 평가 가능하고, 창구가 지나면 닫힌다")
    void reviewWindowFollowsEndedAt() {
        Team team = new Team();
        LocalDateTime ended = LocalDateTime.of(2026, 1, 1, 0, 0);
        team.setEndedAt(ended);

        assertThat(team.isEnded()).isTrue();
        assertThat(team.isReviewableAt(ended.plusDays(1), 14)).isTrue();
        assertThat(team.isReviewableAt(ended.plusDays(14), 14)).isFalse();
        assertThat(team.reviewDeadline(14)).isEqualTo(ended.plusDays(14));
    }

    @Test
    @DisplayName("persist/update 콜백이 createdAt/updatedAt 을 채운다")
    void timestampsFromCallbacks() {
        Team team = new Team();
        team.onCreate();
        assertThat(team.getCreatedAt()).isNotNull();
        assertThat(team.getUpdatedAt()).isEqualTo(team.getCreatedAt());

        LocalDateTime created = team.getCreatedAt();
        team.onUpdate();
        assertThat(team.getUpdatedAt()).isAfterOrEqualTo(created);
    }

    @Test
    @DisplayName("정원이 없으면 가득 찬 것으로 보지 않는다")
    void nullCapacityIsNeverFull() {
        Team team = new Team();
        assertThat(team.isFullWith(99)).isFalse();
        team.setCapacity(3);
        assertThat(team.isFullWith(3)).isTrue();
        assertThat(team.isFullWith(2)).isFalse();
    }

    @Test
    @DisplayName("joinedAt 이 비어 있으면 persist 때 채우고, 이미 있으면 건드리지 않는다")
    void memberOnCreateFillsJoinedAtOnlyWhenMissing() {
        TeamMember missing = TeamMember.of(new Team(), User.builder().name("a").build(),
          TeamMemberRole.MEMBER);
        missing.onCreate();
        assertThat(missing.getJoinedAt()).isNotNull();

        LocalDateTime existing = LocalDateTime.of(2020, 1, 1, 0, 0);
        TeamMember preset = TeamMember.builder()
          .team(new Team())
          .user(User.builder().name("b").build())
          .role(TeamMemberRole.LEADER)
          .joinedAt(existing)
          .build();
        preset.onCreate();
        assertThat(preset.getJoinedAt()).isEqualTo(existing);
    }

    @Test
    @DisplayName("leftAt 이 있으면 비활성이다")
    void leftMemberIsNotActive() {
        TeamMember active = TeamMember.of(new Team(), User.builder().name("a").build(),
          TeamMemberRole.MEMBER);
        assertThat(active.isActive()).isTrue();

        TeamMember left = TeamMember.builder()
          .leftAt(LocalDateTime.now())
          .role(TeamMemberRole.MEMBER)
          .build();
        assertThat(left.isActive()).isFalse();
    }

    @Test
    @DisplayName("평가 점수는 1~5 만 통과하고 null 은 거절한다")
    void ratingBounds() {
        assertThat(TeamReview.isValidRating(null)).isFalse();
        assertThat(TeamReview.isValidRating(0)).isFalse();
        assertThat(TeamReview.isValidRating(1)).isTrue();
        assertThat(TeamReview.isValidRating(5)).isTrue();
        assertThat(TeamReview.isValidRating(6)).isFalse();
    }

    @Test
    @DisplayName("평가 persist 콜백은 createdAt 이 없을 때만 채운다")
    void reviewOnCreateFillsCreatedAtOnlyWhenMissing() {
        TeamReview empty = TeamReview.builder().build();
        empty.onCreate();
        assertThat(empty.getCreatedAt()).isNotNull();

        LocalDateTime existing = LocalDateTime.of(2020, 1, 1, 0, 0);
        TeamReview preset = TeamReview.builder().createdAt(existing).build();
        preset.onCreate();
        assertThat(preset.getCreatedAt()).isEqualTo(existing);
    }
}

package com.example.mateon.teams.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.teams.dto.request.TeamReviewSubmitRequestDTO;
import com.example.mateon.teams.dto.response.TeamReviewTargetsResponseDTO;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserCollaborationScoreRepository;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 평가 제출 전 과정을 실제 DB 에 대고 확인한다 — 검증 순서, 집계 증분, UNIQUE 제약까지.
 *
 * <p>@Transactional 이라 테스트가 끝나면 전부 롤백된다 (개발 DB 를 더럽히지 않는다).
 */
@SpringBootTest
@Transactional
class TeamReviewServiceIntegrationTest {

    @Autowired TeamReviewService teamReviewService;
    @Autowired TeamCompletionService teamCompletionService;
    @Autowired TeamRepository teamRepository;
    @Autowired TeamMemberRepository teamMemberRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserCollaborationScoreRepository scoreRepository;

    private User leader;
    private User memberA;
    private User memberB;
    private Team team;

    @BeforeEach
    void setUp() {
        leader = createUser("팀장");
        memberA = createUser("팀원A");
        memberB = createUser("팀원B");

        team = new Team();
        team.setTitle("협업 온도 테스트 팀");
        team.setLeaderUserId(leader.getId());
        team.setCapacity(3);
        teamRepository.save(team);

        teamMemberRepository.save(TeamMember.of(team, leader, TeamMemberRole.LEADER));
        teamMemberRepository.save(TeamMember.of(team, memberA, TeamMemberRole.MEMBER));
        teamMemberRepository.save(TeamMember.of(team, memberB, TeamMemberRole.MEMBER));
    }

    private User createUser(String name) {
        return userRepository.save(User.builder()
                .email(UUID.randomUUID() + "@test.ac.kr")
                .name(name)
                .schoolVerified(true)
                .build());
    }

    private TeamReviewSubmitRequestDTO request(Long revieweeId, int rating) {
        TeamReviewSubmitRequestDTO.Item item = new TeamReviewSubmitRequestDTO.Item();
        item.setRevieweeId(revieweeId);
        item.setRating(rating);

        TeamReviewSubmitRequestDTO dto = new TeamReviewSubmitRequestDTO();
        dto.setReviews(List.of(item));
        return dto;
    }

    private void endTeam() {
        teamCompletionService.completeByLeader(team.getId(), leader.getId());
    }

    @Test
    @DisplayName("종료 전에는 평가할 수 없다")
    void cannotReviewBeforeCompletion() {
        assertThatThrownBy(() -> teamReviewService.submit(team.getId(), leader.getId(), request(memberA.getId(), 5)))
                .isInstanceOf(MateonException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEAM_NOT_ENDED);
    }

    @Test
    @DisplayName("팀장이 종료하면 모집도 함께 마감되고 평가가 열린다")
    void completionOpensReview() {
        endTeam();

        Team reloaded = teamRepository.findById(team.getId()).orElseThrow();
        assertThat(reloaded.isEnded()).isTrue();
        assertThat(reloaded.getIsRecruiting()).isFalse();
    }

    @Test
    @DisplayName("이미 종료된 팀은 다시 종료할 수 없다")
    void cannotCompleteTwice() {
        endTeam();

        assertThatThrownBy(() -> teamCompletionService.completeByLeader(team.getId(), leader.getId()))
                .isInstanceOf(MateonException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEAM_ALREADY_ENDED);
    }

    @Test
    @DisplayName("팀장이 아니면 종료할 수 없다")
    void onlyLeaderCanComplete() {
        assertThatThrownBy(() -> teamCompletionService.completeByLeader(team.getId(), memberA.getId()))
                .isInstanceOf(MateonException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_ACCESS);
    }

    @Test
    @DisplayName("평가 대상 목록에 자기 자신은 빠지고 나머지 팀원만 나온다")
    void targetsExcludeSelf() {
        endTeam();

        TeamReviewTargetsResponseDTO targets = teamReviewService.getTargets(team.getId(), leader.getId());

        assertThat(targets.getTargets()).hasSize(2);
        assertThat(targets.getTargets()).extracting(TeamReviewTargetsResponseDTO.Target::getUserId)
                .containsExactlyInAnyOrder(memberA.getId(), memberB.getId());
        assertThat(targets.getTargets()).allMatch(t -> !t.isAlreadyReviewed());
    }

    @Test
    @DisplayName("평가 2건이 쌓이면 온도가 공개된다 — 1건까지는 비공개")
    void temperatureDisclosedAtTwoReviews() {
        endTeam();

        teamReviewService.submit(team.getId(), leader.getId(), request(memberA.getId(), 5));

        // 1건: 집계는 쌓이지만 익명성 때문에 온도는 아직 null 이다.
        var afterFirst = scoreRepository.findById(memberA.getId()).orElseThrow();
        assertThat(afterFirst.getReviewCount()).isEqualTo(1);
        assertThat(afterFirst.getRatingSum()).isEqualTo(5);
        assertThat(afterFirst.getTemperature()).isNull();

        teamReviewService.submit(team.getId(), memberB.getId(), request(memberA.getId(), 5));

        var afterSecond = scoreRepository.findById(memberA.getId()).orElseThrow();
        assertThat(afterSecond.getReviewCount()).isEqualTo(2);
        assertThat(afterSecond.getRatingSum()).isEqualTo(10);
        // (5*3 + 10)/(5+2)=3.571 → q=0.286 → E=2/22=0.0909 → 36.5 + 62.5*0.026 = 38.1
        assertThat(afterSecond.getTemperature()).isEqualByComparingTo("38.1");
    }

    @Test
    @DisplayName("같은 대상을 두 번 평가할 수 없다")
    void cannotReviewSameTargetTwice() {
        endTeam();
        teamReviewService.submit(team.getId(), leader.getId(), request(memberA.getId(), 5));

        assertThatThrownBy(() -> teamReviewService.submit(team.getId(), leader.getId(), request(memberA.getId(), 4)))
                .isInstanceOf(MateonException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_REVIEWED);
    }

    @Test
    @DisplayName("자기 자신은 평가할 수 없다")
    void cannotReviewSelf() {
        endTeam();

        assertThatThrownBy(() -> teamReviewService.submit(team.getId(), leader.getId(), request(leader.getId(), 5)))
                .isInstanceOf(MateonException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANNOT_REVIEW_SELF);
    }

    @Test
    @DisplayName("팀원이 아닌 사람은 평가를 제출할 수 없다")
    void outsiderCannotSubmit() {
        endTeam();
        User outsider = createUser("외부인");

        assertThatThrownBy(() -> teamReviewService.submit(team.getId(), outsider.getId(), request(memberA.getId(), 5)))
                .isInstanceOf(MateonException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_TEAM_MEMBER);
    }

    @Test
    @DisplayName("팀원이 아닌 사람은 평가 대상이 될 수 없다")
    void outsiderCannotBeReviewed() {
        endTeam();
        User outsider = createUser("외부인");

        assertThatThrownBy(() -> teamReviewService.submit(team.getId(), leader.getId(), request(outsider.getId(), 5)))
                .isInstanceOf(MateonException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_TEAM_MEMBER);
    }

    @Test
    @DisplayName("평가 기간(14일)이 지나면 제출이 거부된다")
    void rejectsAfterDeadline() {
        endTeam();

        Team reloaded = teamRepository.findById(team.getId()).orElseThrow();
        reloaded.setEndedAt(LocalDateTime.now().minusDays(15));

        assertThatThrownBy(() -> teamReviewService.submit(team.getId(), leader.getId(), request(memberA.getId(), 5)))
                .isInstanceOf(MateonException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REVIEW_PERIOD_EXPIRED);
    }

    @Test
    @DisplayName("제출한 대상은 대상 목록에서 '완료'로 표시된다")
    void submittedTargetIsMarked() {
        endTeam();
        teamReviewService.submit(team.getId(), leader.getId(), request(memberA.getId(), 4));

        TeamReviewTargetsResponseDTO targets = teamReviewService.getTargets(team.getId(), leader.getId());

        assertThat(targets.getTargets())
                .filteredOn(t -> t.getUserId().equals(memberA.getId()))
                .allMatch(TeamReviewTargetsResponseDTO.Target::isAlreadyReviewed);
        assertThat(targets.getTargets())
                .filteredOn(t -> t.getUserId().equals(memberB.getId()))
                .allMatch(t -> !t.isAlreadyReviewed());
    }
}

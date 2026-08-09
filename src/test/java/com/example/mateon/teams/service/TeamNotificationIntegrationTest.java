package com.example.mateon.teams.service;

import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.repository.NotificationRepository;
import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.teams.dto.request.TeamApplicationRequestDTO;
import com.example.mateon.teams.dto.response.TeamOfferResponseDTO;
import com.example.mateon.teams.repository.TeamApplicationRepository;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림이 실제로 <b>DB 에 남는지</b>를 확인한다. 단위 테스트가 "누구에게 보내기로 했는가"를 잠근다면,
 * 이쪽은 그 결정이 트랜잭션을 넘어 영속화되는지를 본다.
 *
 * <p>이 구분이 필요한 이유가 있다. 예전에 SSE 전송 실패가 IllegalStateException 으로 올라와
 * 호출자 트랜잭션을 rollback-only 로 만들었고, 그 결과 <b>send 는 호출됐는데 알림 행은 없는</b>
 * 상태가 됐다. 목으로 send 호출만 세는 테스트는 그런 사고를 절대 못 잡는다.
 *
 * <p>특히 팀 삭제가 핵심이다 — 팀·지원서·제안이 전부 사라진 뒤에도 알림만은 남아야 한다.
 * 알림이 팀을 참조했다면 CASCADE 에 함께 쓸려 나갔을 것이다.
 *
 * <p>구독 중인 emitter 로의 실시간 push 는 여기서 일어나지 않는다. AFTER_COMMIT 리스너가 맡는데
 * 테스트는 롤백으로 끝나기 때문이다. 의도한 바다 — 여기서 보려는 것은 저장이지 전송이 아니다.
 */
class TeamNotificationIntegrationTest extends IntegrationTestBase {

    @Autowired TeamService teamService;
    @Autowired TeamOfferService teamOfferService;
    @Autowired TeamCompletionService teamCompletionService;
    @Autowired TeamRepository teamRepository;
    @Autowired TeamApplicationRepository applicationRepository;
    @Autowired TeamMemberRepository teamMemberRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @PersistenceContext EntityManager entityManager;

    private User leader;
    private User applicant;
    private Team team;

    @BeforeEach
    void setUp() {
        leader = createUser("팀장");
        applicant = createUser("지원자");

        team = new Team();
        team.setTitle("알림 통합 테스트 팀");
        team.setLeaderUserId(leader.getId());
        team.setCapacity(4);
        team.setIsRecruiting(true);
        team.setRecruitmentEndDate(LocalDate.now().plusDays(30));
        teamRepository.save(team);

        teamMemberRepository.save(TeamMember.of(team, leader, TeamMemberRole.LEADER));
        endRequest();
    }

    @Test
    @DisplayName("지원서를 내면 팀장 앞으로 알림 행이 쌓인다")
    void applyPersistsNotificationForLeader() {
        teamService.applyToTeam(team.getId(), applicationRequest(), applicant.getId());

        assertThat(titlesOf(leader)).contains("지원서 도착");
    }

    @Test
    @DisplayName("지원을 철회하면 팀장 앞으로 알림 행이 쌓인다")
    void cancelApplicationPersistsNotificationForLeader() {
        teamService.applyToTeam(team.getId(), applicationRequest(), applicant.getId());
        Long applicationId = applicationRepository
                .findByTeamIdAndApplicantId(team.getId(), applicant.getId())
                .orElseThrow()
                .getId();

        teamService.cancelApplication(applicationId, applicant.getId());

        assertThat(titlesOf(leader)).contains("지원 취소");
        // 지원서 자체는 하드 삭제라 흔적이 없다. 알림이 유일한 기록이다.
        assertThat(applicationRepository.findById(applicationId)).isEmpty();
    }

    @Test
    @DisplayName("제안을 회수하면 대상 유저 앞으로 알림 행이 쌓인다")
    void cancelOfferPersistsNotificationForTarget() {
        TeamOfferResponseDTO offer = teamOfferService.createOffer(
                team.getId(), applicant.getId(), "함께해요", leader.getId());

        teamOfferService.cancelOffer(offer.getOfferId(), leader.getId());

        assertThat(titlesOf(applicant)).contains("팀 제안 도착", "제안 취소");
    }

    @Test
    @DisplayName("팀을 지워도 관련자에게 보낸 알림은 남는다")
    void deleteTeamPersistsNotificationsForEveryoneAttached() {
        User offered = createUser("제안받은사람");
        User member = createUser("팀원");
        teamMemberRepository.save(TeamMember.of(team, member, TeamMemberRole.MEMBER));
        teamService.applyToTeam(team.getId(), applicationRequest(), applicant.getId());
        teamOfferService.createOffer(team.getId(), offered.getId(), "함께해요", leader.getId());
        endRequest();

        teamService.deleteTeam(team.getId(), leader.getId());

        // 팀도 지원서도 제안도 사라졌지만 알림은 남아 있어야 한다.
        assertThat(teamRepository.findById(team.getId())).isEmpty();
        assertThat(titlesOf(applicant)).contains("팀 삭제");
        assertThat(titlesOf(offered)).contains("팀 삭제");
        assertThat(titlesOf(member)).contains("팀 삭제");
        // 본인이 지운 팀이다.
        assertThat(titlesOf(leader)).doesNotContain("팀 삭제");
    }

    @Test
    @DisplayName("팀장이 직접 종료한 1인 팀에는 종료 알림이 남지 않는다")
    void manualCompletionOfSoloTeamLeavesNoNotification() {
        teamCompletionService.completeByLeader(team.getId(), leader.getId());

        assertThat(titlesOf(leader)).doesNotContain("활동 자동 종료", "팀원 평가 요청");
    }

    // --- 헬퍼 ---

    /**
     * 요청 경계를 흉내 낸다.
     *
     * <p>실전에서는 지원·제안·팀 삭제가 각각 별개의 요청이라, 삭제가 시작될 때 앞선 요청이 만든
     * TeamMember/TeamApplication/TeamOffer 는 영속성 컨텍스트에 남아 있지 않다. 반면 이 테스트는
     * 전부 한 트랜잭션이라 그것들이 그대로 살아 있고, team 을 지우는 순간 <b>삭제된 Team 을
     * 참조한 채로</b> flush 되어 TransientPropertyValueException 이 난다. 실제로는 일어나지 않는
     * 상황이므로 명시적으로 컨텍스트를 비워 조건을 맞춘다.
     */
    private void endRequest() {
        entityManager.flush();
        entityManager.clear();
    }

    private User createUser(String name) {
        return userRepository.save(User.builder()
                .email(UUID.randomUUID() + "@test.ac.kr")
                .name(name)
                .schoolVerified(true)
                .build());
    }

    private TeamApplicationRequestDTO applicationRequest() {
        TeamApplicationRequestDTO dto = new TeamApplicationRequestDTO();
        dto.setIntroduction("소개");
        dto.setMessage("지원 동기");
        dto.setContactNumber("010-0000-0000");
        return dto;
    }

    private List<String> titlesOf(User receiver) {
        return notificationRepository.findAllByReceiverIdOrderByCreatedAtDesc(receiver.getId())
                .stream()
                .map(Notification::getTitle)
                .toList();
    }
}

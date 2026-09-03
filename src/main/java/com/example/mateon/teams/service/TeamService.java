package com.example.mateon.teams.service;

import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.matching.event.CandidateSelectedEvent;
import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.teams.domain.ApplicationStatus;
import com.example.mateon.teams.domain.OfferStatus;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamApplication;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.teams.dto.request.TeamApplicationRequestDTO;
import com.example.mateon.teams.dto.request.TeamRequestDTO;
import com.example.mateon.teams.dto.response.TeamApplicationResponseDTO;
import com.example.mateon.teams.dto.response.TeamDetailResponseDTO;
import com.example.mateon.teams.dto.response.TeamResponseDTO;
import com.example.mateon.teams.event.TeamEmbeddingRefreshRequestedEvent;
import com.example.mateon.teams.repository.TeamApplicationRepository;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamOfferRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserCollaborationScore;
import com.example.mateon.user.repository.UserCollaborationScoreRepository;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamApplicationRepository applicationRepository;
    private final TeamOfferRepository offerRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final UserCollaborationScoreRepository collaborationScoreRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    // 1. 팀 모집글 로직
    @Transactional(readOnly = true)
    public List<TeamResponseDTO> getTeams(Long eventId, String category, boolean myPosts, Long userId) {
        List<Team> teams;

        if (myPosts) {
            User user = getUserById(userId);
            teams = teamRepository.findByLeaderUserId(user.getId());
        } else if (eventId != null) {
            teams = teamRepository.findByEventIdAndIsRecruitingTrue(eventId);
        } else if (category != null) {
            // 카테고리가 자율이면 이벤트 없는 팀들만 조회
            if (category.equals("자율")) {
                teams = teamRepository.findAllByEventIdIsNull();
            } else if (!category.equals("전체")) {
                teams = teamRepository.findByEventCategory(category);
            } else {
                teams = teamRepository.findAll();
            }
        } else {
            teams = teamRepository.findAll();
        }

        return teams.stream()
          .map(team -> {
              // 이벤트 조회 (Null 체크 필수)
              Event event = null;
              if (team.getEventId() != null) {
                  event = eventRepository.findById(team.getEventId()).orElse(null);
              }
              int currentCount = teamMemberRepository.countByTeamIdAndLeftAtIsNull(team.getId());

              return new TeamResponseDTO(team, event, currentCount);
          })
          .collect(Collectors.toList());
    }

    // 개별 팀 상세 조회 (리더 여부, 지원 여부 포함)
    @Transactional(readOnly = true)
    public TeamDetailResponseDTO getTeamDetail(Long teamId, Long userId) {
        // 1. 팀 조회
        Team team = teamRepository.findById(teamId)
          .orElseThrow(ErrorCode.RESOURCE_NOT_FOUND::toException);

        // 2. 이벤트 조회
        Event event = null;
        if (team.getEventId() != null) {
            event = eventRepository.findById(team.getEventId()).orElse(null);
        }

        // 3. 팀원 명단 (팀장 포함 — team_members 에 LEADER 로 들어 있다).
        //    인원 수는 DTO 가 이 목록의 크기로 채운다. 따로 세면 명단과 숫자가 어긋날 수 있다.
        List<TeamMember> members = teamMemberRepository.findActiveMembersWithUser(team.getId());

        // 4. 유저 상태 확인 (로그인 했을 경우에만)
        boolean isLeader = false;
        ApplicationStatus myApplicationStatus = null;

        if (userId != null) {
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                // 내가 리더인가?
                isLeader = team.getLeaderUserId().equals(user.getId());
                // 내가 지원했는가? 상태까지 함께 꺼낸다 (hasApplied 는 DTO 가 여기서 파생시킨다).
                myApplicationStatus = applicationRepository.findByTeamIdAndApplicantId(teamId, user.getId())
                  .map(TeamApplication::getStatus)
                  .orElse(null);
            }
        }

        // 5. 팀장(글쓴이) 정보. 이미 읽은 명단에 LEADER 로 들어 있으므로 재사용한다.
        //    V12 백필 이전에 만들어져 LEADER 행이 없는 팀이 있을 수 있어 조회로 fallback 한다.
        User leaderUser = members.stream()
          .filter(member -> member.getRole() == TeamMemberRole.LEADER)
          .map(TeamMember::getUser)
          .findFirst()
          .orElseGet(() -> userRepository.findById(team.getLeaderUserId())
          .orElseThrow(ErrorCode.USER_NOT_FOUND::toException));

        // 6. 팀장의 협업 온도. 평가를 안 받았으면 행이 없고, 그건 0건과 같으므로 기준점을 내려보낸다.
        BigDecimal leaderTemperature = collaborationScoreRepository.findById(leaderUser.getId())
          .map(UserCollaborationScore::getTemperature)
          .orElse(CollaborationTemperatureCalculator.INITIAL);

        // 7. DTO 생성 시 leaderUser 전달
        return new TeamDetailResponseDTO(team, event, members, isLeader, myApplicationStatus, leaderUser,
          leaderTemperature);
    }

    public TeamResponseDTO createTeam(TeamRequestDTO request, Long userId) {
        User user = getUserById(userId);
        requireSchoolVerified(user); // 팀 모집글 작성은 학교 인증(재학생) 필요
        Team team = request.toEntity(user.getId());
        teamRepository.save(team);

        // 팀장도 멤버다. leader_user_id 와 이중 기록이지만, 인원 집계와 평가 대상 조회가
        // team_members 한 곳만 보면 되도록 여기서 행을 만들어 둔다.
        teamMemberRepository.save(TeamMember.of(team, user, TeamMemberRole.LEADER));

        // 커밋 후 비동기로 AI 임베딩 계산 (TeamEmbeddingRefreshListener)
        eventPublisher.publishEvent(new TeamEmbeddingRefreshRequestedEvent(team.getId()));

        Event event = null;
        if (request.getEventId() != null) {
            event = eventRepository.findById(request.getEventId())
              .orElseThrow(ErrorCode.RESOURCE_NOT_FOUND::toException);
        }
        // 갓 만든 팀의 인원은 팀장 1명.
        return new TeamResponseDTO(team, event, 1);
    }

    public TeamResponseDTO updateTeam(Long teamId, TeamRequestDTO request, Long userId) {
        Team team = teamRepository.findById(teamId)
          .orElseThrow(ErrorCode.RESOURCE_NOT_FOUND::toException);
        User user = getUserById(userId);

        if (!team.getLeaderUserId().equals(user.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        team.setTitle(request.getTitle());
        team.setCapacity(request.getCapacity());
        team.setPromotionText(request.getPromotionText());
        team.setRole(request.getRole());
        team.setCharacteristic(request.getCharacteristic());
        team.setRequiredSkills(request.getRequiredSkills());
        team.setRecruitmentStartDate(request.getRecruitmentStartDate());
        team.setRecruitmentEndDate(request.getRecruitmentEndDate());

        // 커밋 후 비동기로 AI 임베딩 재계산 (변경 필드 diff 없이 항상 재계산 — 멱등)
        eventPublisher.publishEvent(new TeamEmbeddingRefreshRequestedEvent(team.getId()));

        Event event = null;
        if (team.getEventId() != null) {
            event = eventRepository.findById(team.getEventId()).orElse(null);
        }
        int currentCount = teamMemberRepository.countByTeamIdAndLeftAtIsNull(team.getId());
        return new TeamResponseDTO(team, event, currentCount);
    }

    public void deleteTeam(Long teamId, Long userId) {
        Team team = teamRepository.findById(teamId)
          .orElseThrow(ErrorCode.RESOURCE_NOT_FOUND::toException);
        User user = getUserById(userId);

        if (!team.getLeaderUserId().equals(user.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        // 하드 삭제라 행이 사라진 뒤에는 알릴 대상을 알 수 없다. 지우기 전에 모은다.
        notifyTeamDeleted(team);

        applicationRepository.deleteByTeamId(teamId);
        // 제안도 함께 지운다. DB 는 FK CASCADE 로 정리하지만, 지원서와 대칭을 맞추고
        // 영속성 컨텍스트에 남은 제안이 팀 삭제 뒤에 flush 되는 일을 막는다.
        offerRepository.deleteByTeamId(teamId);
        teamRepository.delete(team);
    }

    /**
     * 팀이 사라진다는 사실을 이 팀에 걸려 있던 사람 전원에게 알린다.
     *
     * <p>
     * 지원서·제안은 하드 삭제고 team_members 는 DB CASCADE 라, 이 알림이 없으면 목록에서
     * 행이 증발하는 것 말고는 아무 신호가 남지 않는다. 확정된 팀원조차 마이페이지의 참여 활동이
     * 조용히 사라질 뿐이다.
     *
     * <p>
     * <b>받는 사람만 조회하고 소유 엔티티(TeamMember/TeamApplication/TeamOffer)는 절대 올리지
     * 않는다.</b> 올리는 순간 아래에서 team 을 지운 뒤 flush 될 때 삭제된 Team 을 참조한 채로
     * 남아 TransientPropertyValueException 이 난다. User 는 Team 을 참조하지 않아 안전하다.
     */
    private void notifyTeamDeleted(Team team) {
        String content = String.format("[%s] 팀이 삭제되었습니다.", team.getTitle());

        // 한 사람이 팀원이면서 제안 대상일 수 있다. 유저 단위로 합쳐 중복 발송을 막는다.
        Map<Long, User> receivers = new LinkedHashMap<>();
        // 이미 거절·취소된 지원서/제안의 상대는 뺀다 — 그쪽은 이미 끝난 관계다.
        Stream.of(teamMemberRepository.findActiveMemberUsers(team.getId()),
          applicationRepository.findApplicantsByTeamIdAndStatus(
            team.getId(), ApplicationStatus.PENDING),
          offerRepository.findTargetUsersByTeamIdAndStatus(
            team.getId(), OfferStatus.PENDING))
          .flatMap(List::stream)
          .forEach(receiver -> receivers.putIfAbsent(receiver.getId(), receiver));

        receivers.remove(team.getLeaderUserId());  // 본인이 지운 팀이다

        receivers.values().forEach(receiver
          -> notificationService.send(receiver, "팀 삭제", content,
            Notification.NotificationType.INFO));
    }

    // --- 2. 지원(Application) 로직 ---
    public void applyToTeam(Long teamId, TeamApplicationRequestDTO request, Long userId) {
        User applicant = getUserById(userId);
        requireSchoolVerified(applicant); // 팀 지원은 학교 인증(재학생) 필요
        Team team = teamRepository.findById(teamId)
          .orElseThrow(ErrorCode.RESOURCE_NOT_FOUND::toException);

        if (team.getLeaderUserId().equals(applicant.getId())) {
            throw new IllegalArgumentException("본인이 개설한 팀에는 지원할 수 없습니다.");
        }

        if (applicationRepository.findByTeamIdAndApplicantId(teamId, applicant.getId()).isPresent()) {
            throw new IllegalArgumentException("이미 지원한 팀입니다.");
        }

        // DTO의 모든 필드를 Entity로 변환
        TeamApplication application = TeamApplication.builder()
          .team(team)
          .applicant(applicant)
          .introduction(request.getIntroduction())
          .message(request.getMessage())
          .contactNumber(request.getContactNumber())
          .portfolioUrl(request.getPortfolioUrl())
          .status(ApplicationStatus.PENDING)
          .build();

        applicationRepository.save(application);

        // 추천 목록에서 고른 팀이었다면 그 선택을 AI 에 피드백한다 (커밋 뒤 별도 스레드).
        // 추천을 거치지 않은 지원이면 수신 측이 이력을 못 찾아 조용히 끝나므로 여기서는
        // 구분하지 않는다 — teams 도메인은 추천 로그를 알 이유가 없다.
        //
        // id 는 save() 반환값이 아니라 넘긴 인스턴스에서 읽는다 (IDENTITY 라 persist 시점에
        // 바로 채워진다). TeamOfferService.createOffer 도 같은 방식이다.
        eventPublisher.publishEvent(CandidateSelectedEvent.userToTeam(
          applicant.getId(), teamId, application.getId()));

        // 팀장에게 알림. 역제안 발송(TeamOfferService.createOffer)의 반대 방향이다.
        // 팀장 계정이 사라진 팀이어도 지원 자체는 성립해야 하므로, 없으면 조용히 건너뛴다
        // (getUserById 는 USER_NOT_FOUND 를 던져 정상 지원까지 막으므로 쓰지 않는다).
        userRepository.findById(team.getLeaderUserId()).ifPresent(leader
          -> notificationService.send(leader, "지원서 도착",
            String.format("[%s] 팀에 %s 님이 지원했습니다.",
              team.getTitle(), applicant.getName()),
            Notification.NotificationType.INFO));
    }

    @Transactional(readOnly = true)
    public List<TeamApplicationResponseDTO> getMyApplications(Long userId) {
        User user = getUserById(userId);
        return applicationRepository.findByApplicantId(user.getId()).stream()
          .map(app -> TeamApplicationResponseDTO.from(app, user.getId()))
          .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TeamApplicationResponseDTO> getApplicationsForMyTeam(Long teamId, Long userId) {
        Team team = teamRepository.findById(teamId)
          .orElseThrow(ErrorCode.RESOURCE_NOT_FOUND::toException);
        User leader = getUserById(userId);

        if (!team.getLeaderUserId().equals(leader.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        return applicationRepository.findByTeamId(teamId).stream()
          .map(app -> TeamApplicationResponseDTO.from(app, leader.getId()))
          .collect(Collectors.toList());
    }

    // [NEW] 지원서 개별 상세 조회
    @Transactional(readOnly = true)
    public TeamApplicationResponseDTO getApplicationDetail(Long applicationId, Long userId) {
        // 1. 지원서 찾기
        TeamApplication application = applicationRepository.findById(applicationId)
          .orElseThrow(ErrorCode.RESOURCE_NOT_FOUND::toException);

        // 2. 현재 요청한 사람(로그인 유저) 찾기
        User currentUser = getUserById(userId);

        // 3. 권한 체크 (중요!)
        // 조건 A: 내가 지원 당사자인가?
        boolean isApplicant = application.getApplicant().getId().equals(currentUser.getId());
        // 조건 B: 내가 이 팀의 팀장인가?
        boolean isTeamLeader = application.getTeam().getLeaderUserId().equals(currentUser.getId());

        // A와 B 둘 다 아니라면 -> 접근 금지 (예외 발생)
        if (!isApplicant && !isTeamLeader) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        // 4. 통과했으면 DTO 반환
        return TeamApplicationResponseDTO.from(application, currentUser.getId());
    }

    // 지원자 승인/거절 처리 + 알림 발송
    public void processApplication(Long applicationId, boolean isApproved, Long userId) {
        TeamApplication application = applicationRepository.findById(applicationId)
          .orElseThrow(ErrorCode.RESOURCE_NOT_FOUND::toException);
        User leader = getUserById(userId);
        Team team = application.getTeam();

        if (!team.getLeaderUserId().equals(leader.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        // 승인/거절은 한 번뿐이다. 특히 승인된 지원서를 거절로 되돌리면 지원서 상태만 바뀌고
        // 아래에서 만든 team_members 행은 활성인 채 남아 인원 수가 실제보다 커진다.
        // (역제안 쪽 TeamOfferService.respond 는 offer.accept()/reject() 안에서 같은 규칙을 이미 강제한다.)
        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new MateonException(ErrorCode.APPLICATION_ALREADY_PROCESSED);
        }

        // 1. 상태 변경
        application.setStatus(isApproved ? ApplicationStatus.APPROVED : ApplicationStatus.REJECTED);
        if (isApproved) {
            User applicant = application.getApplicant();

            // 승인 = 지원서 상태 변경 + 소속 생성. 같은 트랜잭션에서 함께 움직여야 한다.
            // 거절 후 재지원 같은 경로로 이미 행이 있을 수 있어 재활성화도 처리한다.
            teamMemberRepository.findByTeamIdAndUserId(team.getId(), applicant.getId())
              .ifPresentOrElse(
                member -> member.setLeftAt(null),
                () -> teamMemberRepository.save(
                  TeamMember.of(team, applicant, TeamMemberRole.MEMBER)));
        }
        // 2. 알림 발송 로직
        String title = isApproved ? "가입승인" : "가입거절";
        String content = String.format("[%s] 팀 가입이 %s되었습니다.",
          team.getTitle(),
          isApproved ? "승인" : "거절");

        Notification.NotificationType type = isApproved
          ? Notification.NotificationType.APPROVE : Notification.NotificationType.REJECT;

        // 지원자에게 알림 전송
        notificationService.send(application.getApplicant(), title, content, type);

        // 3. 인원 마감 체크 (승인일 때만)
        if (isApproved) {
            // 방금 저장한 멤버 행까지 세려면 flush 가 필요하다 (save 는 아직 INSERT 전일 수 있다).
            teamMemberRepository.flush();
            int memberCount = teamMemberRepository.countByTeamIdAndLeftAtIsNull(team.getId());
            if (team.isFullWith(memberCount)) {
                team.setIsRecruiting(false); // 모집 마감
            }
        }
    }

    // 지원서 수정
    public void updateApplication(Long applicationId, TeamApplicationRequestDTO request, Long userId) {
        User applicant = getUserById(userId);
        TeamApplication application = applicationRepository.findById(applicationId)
          .orElseThrow(ErrorCode.RESOURCE_NOT_FOUND::toException);

        // 1. 작성자 본인인지 확인
        if (!application.getApplicant().getId().equals(applicant.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        // 2. 이미 승인/거절된 지원서는 수정 불가
        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new IllegalArgumentException("이미 처리된 지원서는 수정할 수 없습니다.");
        }

        // 3. 내용 업데이트
        application.setIntroduction(request.getIntroduction());
        application.setMessage(request.getMessage());
        application.setContactNumber(request.getContactNumber());
        application.setPortfolioUrl(request.getPortfolioUrl());

        // save를 호출하지 않아도 @Transactional 때문에 자동 업데이트(Dirty Checking) 되지만, 명시적으로 써도 무방함
    }

    //  지원서 삭제 (지원 취소)
    public void cancelApplication(Long applicationId, Long userId) {
        User applicant = getUserById(userId);
        TeamApplication application = applicationRepository.findById(applicationId)
          .orElseThrow(ErrorCode.RESOURCE_NOT_FOUND::toException);

        // 1. 작성자 본인인지 확인
        if (!application.getApplicant().getId().equals(applicant.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        // 2. 이미 승인/거절된 지원서는 삭제 불가 (기획에 따라 다를 수 있음. 보통은 취소 불가)
        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new IllegalArgumentException("이미 처리된 지원서는 취소할 수 없습니다.");
        }

        // 3. 데이터 삭제. team 은 삭제 전에 잡아 둔다.
        Team team = application.getTeam();
        applicationRepository.delete(application);

        // 4. 팀장에게 알림. 하드 삭제라 목록에서 행이 그냥 사라지므로 알림이 유일한 신호다
        //    (역제안 취소 → 대상 유저 의 거울이다).
        userRepository.findById(team.getLeaderUserId()).ifPresent(leader
          -> notificationService.send(leader, "지원 취소",
            String.format("[%s] 팀에 낸 %s 님의 지원이 취소되었습니다.",
              team.getTitle(), applicant.getName()),
            Notification.NotificationType.INFO));
    }

    private User getUserById(Long userId) {
        return userRepository.findById(userId)
          .orElseThrow(ErrorCode.USER_NOT_FOUND::toException);
    }

    // 학교 인증(재학생) 이 완료된 유저만 허용. 소셜만 로그인한 미인증 유저는 차단.
    private void requireSchoolVerified(User user) {
        if (!user.isSchoolVerified()) {
            throw new MateonException(ErrorCode.SCHOOL_NOT_VERIFIED);
        }
    }
}

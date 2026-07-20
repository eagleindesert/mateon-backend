package com.example.mateon.teams.service;

import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.teams.domain.ApplicationStatus;
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
import java.util.List;
import java.util.stream.Collectors;

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
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));

        // 2. 이벤트 조회
        Event event = null;
        if (team.getEventId() != null) {
            event = eventRepository.findById(team.getEventId()).orElse(null);
        }

        // 3. 현재 인원 수 (팀장 포함 — team_members 에 LEADER 로 들어 있다)
        int currentCount = teamMemberRepository.countByTeamIdAndLeftAtIsNull(team.getId());

        // 4. 유저 상태 확인 (로그인 했을 경우에만)
        boolean isLeader = false;
        boolean hasApplied = false;

        if (userId != null) {
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                // 내가 리더인가?
                isLeader = team.getLeaderUserId().equals(user.getId());
                // 내가 지원했는가?
                hasApplied = applicationRepository.findByTeamIdAndApplicantId(teamId, user.getId()).isPresent();
            }
        }

        // 5. 팀장(글쓴이) 정보 조회
        // 팀의 leaderUserId를 사용해 유저 정보를 가져옵니다.
        User leaderUser = userRepository.findById(team.getLeaderUserId())
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        // 6. 팀장의 협업 온도. 평가를 안 받았으면 행이 없고, 그건 비공개(null)와 같게 다룬다.
        BigDecimal leaderTemperature = collaborationScoreRepository.findById(leaderUser.getId())
                .map(UserCollaborationScore::getTemperature)
                .orElse(null);

        // 7. DTO 생성 시 leaderUser 전달
        return new TeamDetailResponseDTO(team, event, currentCount, isLeader, hasApplied, leaderUser,
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
                    .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
        }
        // 갓 만든 팀의 인원은 팀장 1명.
        return new TeamResponseDTO(team, event, 1);
    }

    public TeamResponseDTO updateTeam(Long teamId, TeamRequestDTO request, Long userId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
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
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
        User user = getUserById(userId);

        if (!team.getLeaderUserId().equals(user.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        applicationRepository.deleteByTeamId(teamId);
        // 제안도 함께 지운다. DB 는 FK CASCADE 로 정리하지만, 지원서와 대칭을 맞추고
        // 영속성 컨텍스트에 남은 제안이 팀 삭제 뒤에 flush 되는 일을 막는다.
        offerRepository.deleteByTeamId(teamId);
        teamRepository.delete(team);
    }

    // --- 2. 지원(Application) 로직 ---

    public void applyToTeam(Long teamId, TeamApplicationRequestDTO request, Long userId) {
        User applicant = getUserById(userId);
        requireSchoolVerified(applicant); // 팀 지원은 학교 인증(재학생) 필요
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));

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
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
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
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));

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
        return TeamApplicationResponseDTO.from(application,currentUser.getId());
    }

    // 지원자 승인/거절 처리 + 알림 발송
    public void processApplication(Long applicationId, boolean isApproved, Long userId) {
        TeamApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
        User leader = getUserById(userId);
        Team team = application.getTeam();

        if (!team.getLeaderUserId().equals(leader.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
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

        Notification.NotificationType type = isApproved ?
                Notification.NotificationType.APPROVE : Notification.NotificationType.REJECT;

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
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));

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
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));

        // 1. 작성자 본인인지 확인
        if (!application.getApplicant().getId().equals(applicant.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        // 2. 이미 승인/거절된 지원서는 삭제 불가 (기획에 따라 다를 수 있음. 보통은 취소 불가)
        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new IllegalArgumentException("이미 처리된 지원서는 취소할 수 없습니다.");
        }

        // 3. 데이터 삭제
        applicationRepository.delete(application);
    }

    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
    }

    // 학교 인증(재학생) 이 완료된 유저만 허용. 소셜만 로그인한 미인증 유저는 차단.
    private void requireSchoolVerified(User user) {
        if (!user.isSchoolVerified()) {
            throw new MateonException(ErrorCode.SCHOOL_NOT_VERIFIED);
        }
    }
}
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
import com.example.mateon.teams.dto.request.TeamApplicationRequestDTO;
import com.example.mateon.teams.dto.request.TeamRequestDTO;
import com.example.mateon.teams.dto.response.TeamApplicationResponseDTO;
import com.example.mateon.teams.dto.response.TeamDetailResponseDTO;
import com.example.mateon.teams.dto.response.TeamResponseDTO;
import com.example.mateon.teams.repository.TeamApplicationRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamApplicationRepository applicationRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // 1. 팀 모집글 로직

    @Transactional(readOnly = true)
    public List<TeamResponseDTO> getTeams(Long eventId, String category, boolean myPosts, String userEmail) {
        List<Team> teams;

        if (myPosts) {
            User user = getUserByEmail(userEmail);
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
                    int currentCount = applicationRepository.countByTeamIdAndStatus(team.getId(), ApplicationStatus.APPROVED);

                    return new TeamResponseDTO(team, event, currentCount);
                })
                .collect(Collectors.toList());
    }
    // 개별 팀 상세 조회 (리더 여부, 지원 여부 포함)
    @Transactional(readOnly = true)
    public TeamDetailResponseDTO getTeamDetail(Long teamId, String userEmail) {
        // 1. 팀 조회
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));

        // 2. 이벤트 조회
        Event event = null;
        if (team.getEventId() != null) {
            event = eventRepository.findById(team.getEventId()).orElse(null);
        }

        // 3. 현재 인원 수
        int currentCount = applicationRepository.countByTeamIdAndStatus(team.getId(), ApplicationStatus.APPROVED);

        // 4. 유저 상태 확인 (로그인 했을 경우에만)
        boolean isLeader = false;
        boolean hasApplied = false;

        if (userEmail != null) {
            User user = userRepository.findByEmail(userEmail).orElse(null);
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

        // 6. DTO 생성 시 leaderUser 전달
        return new TeamDetailResponseDTO(team, event, currentCount, isLeader, hasApplied, leaderUser);
    }

    public TeamResponseDTO createTeam(TeamRequestDTO request, String userEmail) {
        User user = getUserByEmail(userEmail);
        Team team = request.toEntity(user.getId());
        teamRepository.save(team);

        Event event = null;
        if (request.getEventId() != null) {
            event = eventRepository.findById(request.getEventId())
                    .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
        }
        return new TeamResponseDTO(team, event, 0);
    }

    public TeamResponseDTO updateTeam(Long teamId, TeamRequestDTO request, String userEmail) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
        User user = getUserByEmail(userEmail);

        if (!team.getLeaderUserId().equals(user.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        team.setTitle(request.getTitle());
        team.setCapacity(request.getCapacity());
        team.setPromotionText(request.getPromotionText());
        team.setRole(request.getRole());
        team.setCharacteristic(request.getCharacteristic());
        team.setRecruitmentStartDate(request.getRecruitmentStartDate());
        team.setRecruitmentEndDate(request.getRecruitmentEndDate());

        Event event = null;
        if (team.getEventId() != null) {
            event = eventRepository.findById(team.getEventId()).orElse(null);
        }
        int currentCount = applicationRepository.countByTeamIdAndStatus(team.getId(), ApplicationStatus.APPROVED);
        return new TeamResponseDTO(team, event, currentCount);
    }

    public void deleteTeam(Long teamId, String userEmail) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
        User user = getUserByEmail(userEmail);

        if (!team.getLeaderUserId().equals(user.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        applicationRepository.deleteByTeamId(teamId);
        teamRepository.delete(team);
    }

    // --- 2. 지원(Application) 로직 ---

    public void applyToTeam(Long teamId, TeamApplicationRequestDTO request, String userEmail) {
        User applicant = getUserByEmail(userEmail);
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
    public List<TeamApplicationResponseDTO> getMyApplications(String userEmail) {
        User user = getUserByEmail(userEmail);
        return applicationRepository.findByApplicantId(user.getId()).stream()
                .map(app -> TeamApplicationResponseDTO.from(app, user.getId()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TeamApplicationResponseDTO> getApplicationsForMyTeam(Long teamId, String userEmail) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
        User leader = getUserByEmail(userEmail);

        if (!team.getLeaderUserId().equals(leader.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        return applicationRepository.findByTeamId(teamId).stream()
                .map(app -> TeamApplicationResponseDTO.from(app, leader.getId()))
                .collect(Collectors.toList());
    }
    // [NEW] 지원서 개별 상세 조회
    @Transactional(readOnly = true)
    public TeamApplicationResponseDTO getApplicationDetail(Long applicationId, String userEmail) {
        // 1. 지원서 찾기
        TeamApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));

        // 2. 현재 요청한 사람(로그인 유저) 찾기
        User currentUser = getUserByEmail(userEmail);

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
    public void processApplication(Long applicationId, boolean isApproved, String userEmail) {
        TeamApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
        User leader = getUserByEmail(userEmail);
        Team team = application.getTeam();

        if (!team.getLeaderUserId().equals(leader.getId())) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        // 1. 상태 변경
        application.setStatus(isApproved ? ApplicationStatus.APPROVED : ApplicationStatus.REJECTED);
        if (isApproved) {
            User applicant = application.getApplicant();
            // 기존 분석 리포트를 날려서, 다음 마이페이지 접속 시
            // 이 '새로운 활동'을 포함해 다시 분석하도록 유도함.
            applicant.setDreamyReport(null);
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
            int currentCount = applicationRepository.countByTeamIdAndStatus(team.getId(), ApplicationStatus.APPROVED);
            // 만약 (현재 승인된 인원) >= (모집 정원) 이라면
            if (currentCount >= team.getCapacity()) {
                team.setIsRecruiting(false); // 모집 마감
            }
        }
    }
    // 지원서 수정
    public void updateApplication(Long applicationId, TeamApplicationRequestDTO request, String userEmail) {
        User applicant = getUserByEmail(userEmail);
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
    public void cancelApplication(Long applicationId, String userEmail) {
        User applicant = getUserByEmail(userEmail);
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

    private User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
    }
}
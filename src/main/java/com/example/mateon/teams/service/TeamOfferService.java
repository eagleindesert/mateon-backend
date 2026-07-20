package com.example.mateon.teams.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.domain.TeamToUserRecommendationItem;
import com.example.mateon.matching.repository.TeamToUserRecommendationLogRepository;
import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.teams.domain.TeamOffer;
import com.example.mateon.teams.dto.response.TeamOfferResponseDTO;
import com.example.mateon.teams.repository.TeamApplicationRepository;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamOfferRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 역제안(팀→유저) 처리.
 *
 * <p>{@link TeamService} 의 지원서 흐름과 방향이 반대다 — 저쪽은 유저가 요청하고 팀장이
 * 승인하지만, 이쪽은 팀장이 요청하고 유저가 승인한다. 수락되면 팀장의 재승인 없이 곧바로
 * 팀원이 된다.
 *
 * <p>팀 합류가 실제로 일어나는 지점이므로 정원 마감 처리는 TeamService.processApplication 과
 * 완전히 같은 순서를 따른다 (멤버 저장 → flush → 인원 집계 → isFullWith). 두 경로가 서로 다른
 * 방식으로 세면 정원을 넘겨 받는 팀이 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TeamOfferService {

    private final TeamOfferRepository offerRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamApplicationRepository applicationRepository;
    private final TeamToUserRecommendationLogRepository recommendationLogRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // ── 팀장: 제안 발송 / 조회 / 취소 ────────────────────────────────────────

    /**
     * 팀장이 유저에게 제안을 보낸다.
     *
     * <p>AI 점수/근거는 요청 본문이 아니라 team_to_user_recommendation_items 에서 서버가 찾아
     * 넣는다 — 프론트가 되보낸 값은 신뢰할 수 없기 때문이다. 추천을 거치지 않고 보낸 제안이면
     * 둘 다 null 로 남고, 그건 정상이다.
     */
    public TeamOfferResponseDTO createOffer(Long teamId, Long targetUserId, String message,
                                            Long leaderId) {
        User leader = getUserById(leaderId);
        requireSchoolVerified(leader);

        Team team = getTeamById(teamId);
        requireLeader(team, leaderId);
        requireOpenForJoining(team);

        if (targetUserId.equals(leaderId)) {
            throw new MateonException(ErrorCode.INVALID_INPUT);
        }
        User targetUser = getUserById(targetUserId);

        // 이미 팀원이거나 지원서를 낸 사람에게 제안할 이유가 없다 (추천에서도 빠져 있지만,
        // userId 를 직접 넣어 호출할 수 있으므로 여기서도 막는다).
        if (teamMemberRepository.existsByTeamIdAndUserIdAndLeftAtIsNull(teamId, targetUserId)
                || applicationRepository.findByTeamIdAndApplicantId(teamId, targetUserId).isPresent()
                || offerRepository.existsByTeamIdAndTargetUserId(teamId, targetUserId)) {
            throw new MateonException(ErrorCode.DUPLICATE_RESOURCE);
        }

        Optional<TeamToUserRecommendationItem> recommended =
                recommendationLogRepository.findLatestItem(teamId, targetUserId);

        TeamOffer offer = new TeamOffer(team, targetUser, message,
                recommended.map(TeamToUserRecommendationItem::getScore).orElse(null),
                recommended.map(TeamToUserRecommendationItem::getLabel).orElse(null));

        try {
            offerRepository.saveAndFlush(offer);
        } catch (DataIntegrityViolationException e) {
            // 위 중복 검사와 INSERT 사이에 같은 제안이 들어온 경우. uq_team_offers_pair 가 막아 준다.
            throw new MateonException(ErrorCode.DUPLICATE_RESOURCE);
        }

        notificationService.send(targetUser, "팀 제안 도착",
                String.format("[%s] 팀에서 함께하자는 제안이 왔습니다.", team.getTitle()),
                Notification.NotificationType.INFO);

        return TeamOfferResponseDTO.from(offer, leader);
    }

    @Transactional(readOnly = true)
    public List<TeamOfferResponseDTO> getTeamOffers(Long teamId, Long leaderId) {
        Team team = getTeamById(teamId);
        requireLeader(team, leaderId);

        User leader = getUserById(leaderId);
        return offerRepository.findByTeamIdOrderByCreatedAtDesc(teamId).stream()
                .map(offer -> TeamOfferResponseDTO.from(offer, leader))
                .toList();
    }

    /** 팀장이 아직 응답받지 않은 제안을 회수한다. */
    public void cancelOffer(Long offerId, Long leaderId) {
        TeamOffer offer = getOfferById(offerId);
        requireLeader(offer.getTeam(), leaderId);

        offer.cancel();  // PENDING 이 아니면 OFFER_ALREADY_RESPONDED
    }

    // ── 유저: 받은 제안 조회 / 응답 ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TeamOfferResponseDTO> getMyOffers(Long userId) {
        List<TeamOffer> offers = offerRepository.findByTargetUserIdOrderByCreatedAtDesc(userId);

        // 팀장 이름은 팀마다 하나뿐이므로 한 번에 모아 온다 (제안마다 조회하면 N+1).
        Map<Long, User> leadersById = userRepository.findAllById(
                        offers.stream().map(offer -> offer.getTeam().getLeaderUserId()).distinct().toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return offers.stream()
                // 팀장 계정이 사라졌으면 이름만 비고 나머지는 정상 표시된다.
                .map(offer -> TeamOfferResponseDTO.from(offer,
                        leadersById.get(offer.getTeam().getLeaderUserId())))
                .toList();
    }

    /**
     * 제안을 받은 유저가 수락/거절한다. 수락하면 그 자리에서 팀원이 된다.
     *
     * <p>수락 시점에 팀 상태를 다시 확인하는 게 중요하다 — 제안을 보낸 뒤 유저가 응답하기까지
     * 얼마든지 시간이 흐를 수 있고, 그 사이에 정원이 차거나 활동이 끝났을 수 있다.
     */
    public TeamOfferResponseDTO respond(Long offerId, boolean accepted, Long userId) {
        TeamOffer offer = getOfferById(offerId);

        if (!offer.getTargetUser().getId().equals(userId)) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }

        Team team = offer.getTeam();
        User leader = userRepository.findById(team.getLeaderUserId()).orElse(null);

        if (!accepted) {
            offer.reject();
            if (leader != null) {
                notificationService.send(leader, "제안 거절",
                        String.format("[%s] 팀 제안이 거절되었습니다.", team.getTitle()),
                        Notification.NotificationType.REJECT);
            }
            return TeamOfferResponseDTO.from(offer, leader);
        }

        // ── 수락 ──────────────────────────────────────────────────────────────
        User applicant = offer.getTargetUser();
        requireSchoolVerified(applicant);  // 팀 합류는 지원과 마찬가지로 학교 인증이 필요하다
        requireOpenForJoining(team);       // 제안을 보낸 뒤 마감됐을 수 있다

        offer.accept();

        // 지원서 승인(TeamService.processApplication)과 같은 처리다. 거절 후 재가입 같은
        // 경로로 이미 행이 있을 수 있어 재활성화도 처리한다.
        teamMemberRepository.findByTeamIdAndUserId(team.getId(), applicant.getId())
                .ifPresentOrElse(
                        member -> member.setLeftAt(null),
                        () -> teamMemberRepository.save(
                                TeamMember.of(team, applicant, TeamMemberRole.MEMBER)));

        // 방금 저장한 멤버 행까지 세려면 flush 가 필요하다 (save 는 아직 INSERT 전일 수 있다).
        teamMemberRepository.flush();
        int memberCount = teamMemberRepository.countByTeamIdAndLeftAtIsNull(team.getId());
        if (team.isFullWith(memberCount)) {
            team.setIsRecruiting(false);  // 모집 마감
        }

        if (leader != null) {
            notificationService.send(leader, "제안 수락",
                    String.format("[%s] 팀 제안을 %s 님이 수락했습니다.",
                            team.getTitle(), applicant.getName()),
                    Notification.NotificationType.APPROVE);
        }

        return TeamOfferResponseDTO.from(offer, leader);
    }

    // ── 공통 ─────────────────────────────────────────────────────────────────

    private TeamOffer getOfferById(Long offerId) {
        return offerRepository.findById(offerId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Team getTeamById(Long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
    }

    private void requireLeader(Team team, Long userId) {
        if (!team.getLeaderUserId().equals(userId)) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }
    }

    /** 사람을 더 받을 수 있는 팀인지. 모집 마감과 활동 종료는 다른 축이라 둘 다 본다. */
    private void requireOpenForJoining(Team team) {
        if (Boolean.FALSE.equals(team.getIsRecruiting()) || team.isEnded()) {
            throw new MateonException(ErrorCode.TEAM_RECRUITMENT_CLOSED);
        }
    }

    // 학교 인증(재학생) 이 완료된 유저만 허용 — TeamService 와 같은 규칙이다.
    private void requireSchoolVerified(User user) {
        if (!user.isSchoolVerified()) {
            throw new MateonException(ErrorCode.SCHOOL_NOT_VERIFIED);
        }
    }
}

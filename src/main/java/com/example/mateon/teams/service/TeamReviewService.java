package com.example.mateon.teams.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.teams.config.CollaborationProperties;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamReview;
import com.example.mateon.teams.dto.request.TeamReviewSubmitRequestDTO;
import com.example.mateon.teams.dto.response.TeamReviewTargetsResponseDTO;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.teams.repository.TeamReviewRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserCollaborationScore;
import com.example.mateon.user.repository.UserCollaborationScoreRepository;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 팀원 평가 제출과 조회.
 *
 * <p>평가는 <b>완전 익명</b>이다. 이 서비스의 어떤 응답도 "누가 누구에게 몇 점을 줬는지"를
 * 드러내서는 안 된다 — 노출되는 건 유저별 집계(온도)뿐이다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TeamReviewService {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamReviewRepository teamReviewRepository;
    private final UserRepository userRepository;
    private final UserCollaborationScoreRepository scoreRepository;
    private final CollaborationProperties properties;

    /** 내가 평가해야 할 팀원 목록. */
    @Transactional(readOnly = true)
    public TeamReviewTargetsResponseDTO getTargets(Long teamId, Long userId) {
        Team team = requireReviewableTeam(teamId);
        requireMember(teamId, userId);

        // 내가 이미 평가한 대상들. 내 제출 내역이므로 나에게 보여주는 건 익명성과 무관하다.
        Set<Long> reviewed = teamReviewRepository.findByTeamIdAndReviewerId(teamId, userId).stream()
                .map(review -> review.getReviewee().getId())
                .collect(Collectors.toSet());

        List<TeamReviewTargetsResponseDTO.Target> targets =
                teamMemberRepository.findByTeamIdAndLeftAtIsNull(teamId).stream()
                        .map(TeamMember::getUser)
                        .filter(member -> !member.getId().equals(userId)) // 자기 자신은 대상이 아니다
                        .map(member -> new TeamReviewTargetsResponseDTO.Target(
                                member.getId(),
                                member.getName(),
                                member.getMajor(),
                                reviewed.contains(member.getId())))
                        .toList();

        return new TeamReviewTargetsResponseDTO(
                team.getId(),
                team.getTitle(),
                team.getEndedAt(),
                team.reviewDeadline(properties.getReviewWindowDays()),
                targets);
    }

    /**
     * 평가 일괄 제출.
     *
     * <p>하나라도 실패하면 전부 롤백된다 — 절반만 반영된 상태가 남으면 사용자가 무엇을 다시 내야
     * 하는지 알 수 없다.
     */
    public void submit(Long teamId, Long reviewerId, TeamReviewSubmitRequestDTO request) {
        Team team = requireReviewableTeam(teamId);
        requireMember(teamId, reviewerId);

        User reviewer = userRepository.findById(reviewerId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        // 팀원 전원을 한 번에 읽어 두고 검증에 재사용한다 (대상마다 조회하면 N+1).
        Map<Long, User> membersById = teamMemberRepository.findByTeamIdAndLeftAtIsNull(teamId).stream()
                .map(TeamMember::getUser)
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Set<Long> seen = new HashSet<>();

        for (TeamReviewSubmitRequestDTO.Item item : request.getReviews()) {
            Long revieweeId = item.getRevieweeId();

            if (revieweeId.equals(reviewerId)) {
                throw new MateonException(ErrorCode.CANNOT_REVIEW_SELF);
            }
            // 같은 요청 안에서의 중복. DB UNIQUE 로도 막히지만 그 전에 명확한 에러를 준다.
            if (!seen.add(revieweeId)) {
                throw new MateonException(ErrorCode.ALREADY_REVIEWED);
            }
            User reviewee = membersById.get(revieweeId);
            if (reviewee == null) {
                throw new MateonException(ErrorCode.NOT_TEAM_MEMBER);
            }
            if (!TeamReview.isValidRating(item.getRating())) {
                throw new MateonException(ErrorCode.INVALID_RATING);
            }
            if (teamReviewRepository.existsByTeamIdAndReviewerIdAndRevieweeId(teamId, reviewerId, revieweeId)) {
                throw new MateonException(ErrorCode.ALREADY_REVIEWED);
            }

            teamReviewRepository.save(TeamReview.builder()
                    .team(team)
                    .reviewer(reviewer)
                    .reviewee(reviewee)
                    .rating(item.getRating().shortValue())
                    .build());

            applyToScore(revieweeId, item.getRating());
        }
    }

    /** 대상자의 집계를 증분 갱신한다. 온도 재계산은 엔티티가 한다. */
    private void applyToScore(Long revieweeId, int rating) {
        UserCollaborationScore score = scoreRepository.findById(revieweeId)
                .orElseGet(() -> UserCollaborationScore.init(revieweeId));
        score.addRating(rating);
        scoreRepository.save(score);
    }

    /** 종료됐고 평가 기간이 남아 있는 팀인지. */
    private Team requireReviewableTeam(Long teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!team.isEnded()) {
            throw new MateonException(ErrorCode.TEAM_NOT_ENDED);
        }
        if (!team.isReviewableAt(LocalDateTime.now(), properties.getReviewWindowDays())) {
            throw new MateonException(ErrorCode.REVIEW_PERIOD_EXPIRED);
        }
        return team;
    }

    private void requireMember(Long teamId, Long userId) {
        if (!teamMemberRepository.existsByTeamIdAndUserIdAndLeftAtIsNull(teamId, userId)) {
            throw new MateonException(ErrorCode.NOT_TEAM_MEMBER);
        }
    }
}

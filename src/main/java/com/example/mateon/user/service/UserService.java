package com.example.mateon.user.service;

import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.matching.repository.MatchingIntentSlotRepository;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.service.CollaborationTemperatureCalculator;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserCollaborationScore;
import com.example.mateon.user.repository.UserCollaborationScoreRepository;
import com.example.mateon.auth.repository.RefreshTokenRepository;
import com.example.mateon.user.dto.MyPageResponseDTO;
import com.example.mateon.user.dto.PasswordChangeRequest;
import com.example.mateon.user.dto.UserProfileResponse;
import com.example.mateon.user.dto.UserResponse;
import com.example.mateon.user.dto.UserUpdateRequest;
import com.example.mateon.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserCollaborationScoreRepository collaborationScoreRepository;
    private final EventRepository eventRepository;
    private final MatchingIntentSlotRepository matchingIntentSlotRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * 내 프로필. 마이페이지 화면이 필요한 값(협업 온도, 참여 활동)까지 함께 싣는다.
     * {@link #getMyPage} 는 같은 값을 주는 중복 경로이며 프론트가 쓰지 않는다.
     */
    @Transactional(readOnly = true)
    public UserResponse getMyProfile(Long userId) {
        User user = userRepository.findById(userId)
          .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
        return UserResponse.ofFull(
          user,
          loadCollaborationScore(userId),
          loadParticipatedActivities(userId));
    }

    public UserResponse updateMyProfile(Long userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId)
          .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        user.update(
          request.getName(),
          request.getSchool(),
          request.getCampus(),
          request.getCollege(),
          request.getMajor(),
          request.getGrade(),
          request.getInterestJobPrimary(),
          request.getInterestJobSecondary(),
          request.getInterestJobTertiary(),
          request.getTagline(),
          request.getPortfolio()
        );

        userRepository.save(user);
        // GET /me 와 같은 DTO 로 나가므로 온도·활동도 같이 싣는다. 한쪽만 담으면 프로필 수정 직후
        // 화면에서 그 값들이 사라진다.
        return UserResponse.ofFull(
          user,
          loadCollaborationScore(userId),
          loadParticipatedActivities(userId));
    }

    /**
     * 협업 온도 집계. 평가를 한 번도 안 받았으면 행이 없다 — 그때는 0건과 같게 다룬다.
     */
    private UserCollaborationScore loadCollaborationScore(Long userId) {
        return collaborationScoreRepository.findById(userId).orElse(null);
    }

    /**
     * 남이 보는 프로필. 로그인한 사람이면 누구나 부를 수 있으므로 담기는 값은
     * {@link UserProfileResponse} 의 주석대로 연락처를 배제한 공개 항목뿐이다.
     *
     * @param viewerId 조회하는 사람. 대상과 같으면 isMe=true 로 내려간다.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getPublicProfile(Long targetUserId, Long viewerId) {
        User user = userRepository.findById(targetUserId)
          .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        // 슬롯과 온도는 없을 수 있다 — 의도 추출을 안 했거나 평가를 아직 못 받은 유저다.
        // 둘 다 "아직 없음"이 정상 상태라 예외로 다루지 않는다.
        MatchingIntentSlot slot = matchingIntentSlotRepository.findByUserId(targetUserId).orElse(null);
        UserCollaborationScore score = loadCollaborationScore(targetUserId);

        return UserProfileResponse.of(
          user,
          slot,
          score,
          loadParticipatedActivities(targetUserId),
          targetUserId.equals(viewerId));
    }

    /**
     * @deprecated {@link #getMyProfile}({@code GET /api/users/me}) 이 같은 값을 모두 싣는다.
     * 프론트가 쓰지 않는 중복 경로라 폐기 예정이며, 새 호출부를 만들지 말 것.
     * 제거는 프론트 확인 후 별건으로 한다 — 그때까지 동작은 그대로 유지한다.
     */
    @Deprecated
    @Transactional
    public MyPageResponseDTO getMyPage(Long userId) {
        // 1. 유저 정보 조회
        User user = userRepository.findById(userId)
          .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        // 2. 참여한 활동
        List<MyPageResponseDTO.ActivitySummaryDTO> activities = loadParticipatedActivities(user.getId());

        // 3. 협업 온도
        UserCollaborationScore score = loadCollaborationScore(user.getId());

        // 4. DTO 조립 및 반환
        return MyPageResponseDTO.builder()
          .collaborationTemperature(score != null
            ? score.getTemperature()
            : CollaborationTemperatureCalculator.INITIAL)
          .collaborationReviewCount(score != null ? score.getReviewCount() : 0)
          .name(user.getName())
          .college(user.getCollege())
          .major(user.getMajor())
          .grade(user.getGrade())
          .interestJobPrimary(user.getInterestJobPrimary())
          .school(user.getSchool())
          .campus(user.getCampus())
          .schoolVerified(user.isSchoolVerified())
          .profileImageUrl(user.getProfileImageUrl())
          .participatedActivities(activities)
          .build();
    }

    /**
     * 참여한 활동 목록. 내 프로필({@code /me})·마이페이지·공개 프로필이 함께 쓴다.
     *
     * <p>
     * '승인된 지원서'로 세지 않는 이유: 그러면 내가 팀장으로 만든 팀이 내 활동에서 빠진다.
     * team_members 는 팀장을 LEADER 로 함께 담으므로 한 번의 조회로 둘 다 잡힌다.
     *
     * <p>
     * event 를 멤버십마다 findById 하지 않고 한 번에 모아 읽는다 — 호출부가 둘로 늘었고,
     * 활동을 많이 한 유저일수록 프로필이 느려지는 게 눈에 띄기 때문이다.
     */
    private List<MyPageResponseDTO.ActivitySummaryDTO> loadParticipatedActivities(Long userId) {
        List<TeamMember> memberships = teamMemberRepository.findByUserIdAndLeftAtIsNull(userId);

        Set<Long> eventIds = memberships.stream()
          .map(membership -> membership.getTeam().getEventId())
          .filter(Objects::nonNull)
          .collect(Collectors.toSet());

        Map<Long, Event> eventsById = eventIds.isEmpty()
          ? Map.of()
          : eventRepository.findAllById(eventIds).stream()
            .collect(Collectors.toMap(Event::getId, Function.identity()));

        return memberships.stream()
          .map(membership -> {
              Team team = membership.getTeam();
              Event event = team.getEventId() != null ? eventsById.get(team.getEventId()) : null;

              // 연결된 활동이 없거나 카테고리가 비어 있으면 '기타'로 보여준다.
              String category = (event != null && event.getCategory() != null)
                ? event.getCategory().getLabel()
                : Event.Category.ETC.getLabel();

              return MyPageResponseDTO.ActivitySummaryDTO.builder()
                .id(team.getId())
                .title(team.getTitle())
                .category(category)
                .build();
          })
          .collect(Collectors.toList());
    }

    public void changePassword(Long userId, PasswordChangeRequest request) {
        // 새 비밀번호 확인 일치 검증
        if (!request.getNewPassword().equals(request.getNewPasswordConfirm())) {
            throw new MateonException(ErrorCode.PASSWORD_MISMATCH);
        }

        User user = userRepository.findById(userId)
          .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        // 현재 비밀번호 확인
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new MateonException(ErrorCode.PASSWORD_MISMATCH);
        }

        // 새 비밀번호로 업데이트
        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // 비밀번호 변경 시 리프레시 토큰 삭제 (재로그인 필요)
        refreshTokenRepository.deleteByUserId(user.getId());
    }
}

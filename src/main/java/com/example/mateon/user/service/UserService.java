package com.example.mateon.user.service;

import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserCollaborationScore;
import com.example.mateon.user.repository.UserCollaborationScoreRepository;
import com.example.mateon.auth.repository.RefreshTokenRepository;
import com.example.mateon.user.dto.MyPageResponseDTO;
import com.example.mateon.user.dto.PasswordChangeRequest;
import com.example.mateon.user.dto.UserResponse;
import com.example.mateon.user.dto.UserUpdateRequest;
import com.example.mateon.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserCollaborationScoreRepository collaborationScoreRepository;
    private final EventRepository eventRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(readOnly = true)
    public UserResponse getMyProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
        return UserResponse.from(user);
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
                request.getTagline()
        );

        userRepository.save(user);
        return UserResponse.from(user);
    }

    @Transactional
    public MyPageResponseDTO getMyPage(Long userId) {
        // 1. 유저 정보 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        // 2. 참여한 활동 조회.
        //    예전엔 '승인된 지원서'로만 셌는데, 그러면 내가 팀장으로 만든 팀이 내 활동에서 빠졌다.
        //    team_members 는 팀장을 LEADER 로 함께 담으므로 한 번의 조회로 둘 다 잡힌다.
        List<TeamMember> memberships = teamMemberRepository
                .findByUserIdAndLeftAtIsNull(user.getId());

        // 3. 활동 정보를 DTO로 변환 (Team의 title과 Event의 category 사용)
        List<MyPageResponseDTO.ActivitySummaryDTO> activities = memberships.stream()
                .map(membership -> {
                    Team team = membership.getTeam();
                    String activityTitle = team.getTitle();
                    String category = "기타"; // 기본값

                    // Event 정보가 있으면 Category 사용
                    if (team.getEventId() != null) {
                        Event event = eventRepository.findById(team.getEventId()).orElse(null);
                        if (event != null && event.getCategory() != null) {
                            // Category enum을 한글 문자열로 변환
                            switch (event.getCategory().name()) {
                                case "CONTEST":
                                    category = "공모전";
                                    break;
                                case "EXTERNAL":
                                    category = "대외활동";
                                    break;
                                case "SCHOOL":
                                    category = "교내";
                                    break;
                            }
                        }
                    }

                    return MyPageResponseDTO.ActivitySummaryDTO.builder()
                            .id(team.getId())
                            .title(activityTitle)
                            .category(category)
                            .build();
                })
                .collect(Collectors.toList());

        // 4. 협업 온도. 평가를 한 번도 안 받았으면 행이 없다 — 그때는 비공개(null)와 같게 다룬다.
        UserCollaborationScore score = collaborationScoreRepository.findById(user.getId()).orElse(null);

        // 5. DTO 조립 및 반환
        return MyPageResponseDTO.builder()
                .collaborationTemperature(score != null ? score.getTemperature() : null)
                .collaborationReviewCount(score != null ? score.getReviewCount() : 0)
                .name(user.getName())
                .college(user.getCollege())
                .major(user.getMajor())
                .grade(user.getGrade())
                .interestJobPrimary(user.getInterestJobPrimary())
                .school(user.getSchool())
                .campus(user.getCampus())
                .schoolVerified(user.isSchoolVerified())
                .participatedActivities(activities)
                .build();
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
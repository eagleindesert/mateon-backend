package com.example.mateon.user.service;

import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.teams.domain.ApplicationStatus;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamApplication;
import com.example.mateon.teams.repository.TeamApplicationRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.auth.repository.RefreshTokenRepository;
import com.example.mateon.user.dto.MyPageResponseDTO;
import com.example.mateon.user.dto.PasswordChangeRequest;
import com.example.mateon.user.dto.UserResponse;
import com.example.mateon.user.dto.UserUpdateRequest;
import com.example.mateon.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final TeamApplicationRepository teamApplicationRepository;
    private final EventRepository eventRepository;
    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
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
                request.getCampus(),
                request.getCollege(),
                request.getMajor(),
                request.getGrade(),
                request.getInterestJobPrimary(),
                request.getInterestJobSecondary(),
                request.getInterestJobTertiary(),
                request.getTagline()
        );
        user.setDreamyReport(null);

        userRepository.save(user);
        return UserResponse.from(user);
    }

    @Transactional
    public MyPageResponseDTO getMyPage(Long userId) {
        // 1. 유저 정보 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        // 2. 참여한 활동 조회 (승인된 지원서만)
        List<TeamApplication> approvedApplications = teamApplicationRepository
                .findByApplicantIdAndStatus(user.getId(), ApplicationStatus.APPROVED);

        // 3. 활동 정보를 DTO로 변환 (Team의 title과 Event의 category 사용)
        List<MyPageResponseDTO.ActivitySummaryDTO> activities = approvedApplications.stream()
                .map(app -> {
                    Team team = app.getTeam();
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

        // 4. 활동 제목 목록 추출 (AI 분석용)
        List<String> activityTitles = activities.stream()
                .map(MyPageResponseDTO.ActivitySummaryDTO::getTitle)
                .collect(Collectors.toList());

        // 5. 드림이 리포트 생성 또는 조회
        MyPageResponseDTO.AiAnalysisDTO aiAnalysis;
        if (user.getDreamyReport() == null || user.getDreamyReport().isEmpty()) {
            // AI 분석 요청
            String jsonResponse = openAiService.generateDreamyAnalysis(user, activityTitles);
            try {
                aiAnalysis = objectMapper.readValue(jsonResponse, MyPageResponseDTO.AiAnalysisDTO.class);
                // DB에 저장
                user.setDreamyReport(jsonResponse);
                userRepository.save(user);
            } catch (JsonProcessingException e) {
                // 파싱 실패 시 기본값
                aiAnalysis = new MyPageResponseDTO.AiAnalysisDTO(
                        50,
                        "분석 중입니다.",
                        "활동을 더 입력해주세요.",
                        "활동 추가하기"
                );
            }
        } else {
            // DB에 저장된 리포트 파싱
            try {
                aiAnalysis = objectMapper.readValue(user.getDreamyReport(), MyPageResponseDTO.AiAnalysisDTO.class);
            } catch (JsonProcessingException e) {
                // 파싱 실패 시 새로 생성
                String jsonResponse = openAiService.generateDreamyAnalysis(user, activityTitles);
                try {
                    aiAnalysis = objectMapper.readValue(jsonResponse, MyPageResponseDTO.AiAnalysisDTO.class);
                    user.setDreamyReport(jsonResponse);
                    userRepository.save(user);
                } catch (JsonProcessingException ex) {
                    aiAnalysis = new MyPageResponseDTO.AiAnalysisDTO(
                            50,
                            "분석 중입니다.",
                            "활동을 더 입력해주세요.",
                            "활동 추가하기"
                    );
                }
            }
        }

        // 6. DTO 조립 및 반환
        return MyPageResponseDTO.builder()
                .name(user.getName())
                .college(user.getCollege())
                .major(user.getMajor())
                .grade(user.getGrade())
                .interestJobPrimary(user.getInterestJobPrimary())
                .campus(user.getCampus())
                .schoolVerified(user.isSchoolVerified())
                .dreamyReport(aiAnalysis)
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
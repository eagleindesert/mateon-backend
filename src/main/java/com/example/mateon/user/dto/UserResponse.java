package com.example.mateon.user.dto;

import com.example.mateon.teams.service.CollaborationTemperatureCalculator;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserCollaborationScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 프로필 ({@code GET /api/users/me}, {@code PUT /api/users/me}).
 *
 * <p>마이페이지 화면이 필요한 값은 전부 여기에 담긴다. {@code /mypage}({@link MyPageResponseDTO})
 * 가 같은 값을 주지만 그쪽은 프론트가 쓰지 않는 중복 API 이고, 폐기 예정이다. 값을 옮길 때
 * <b>기존 키는 건드리지 않고 필드만 더했다</b>: 이 DTO 를 이미 읽고 있는 화면들이 있으므로 키
 * 구성이 바뀌면 그쪽이 깨진다.
 *
 * <p>협업 온도 2종과 참여 활동은 <b>모든 응답에 담기지 않는다</b>. {@link #from(User)} 로 만든
 * 응답은 세 값이 모두 null 이고, 이는 "비공개"나 "없음"이 아니라 <b>"이 응답은 그 값을 싣지
 * 않는다"</b>는 뜻이다. 지원서 응답의 {@code applicant} 필드가 이 DTO 를 재사용하기 때문에 생기는
 * 구분이며 ({@link com.example.mateon.teams.dto.response.TeamApplicationResponseDTO#applicant}),
 * 실어야 하는 쪽은 {@link #from(User, UserCollaborationScore, List)} 를 쓴다.
 *
 * <p>그래서 {@code collaborationReviewCount} 를 {@code int} 가 아니라 {@link Integer} 로 뒀다.
 * 온도를 싣는 응답에서는 건수가 0 이라도 온도가 함께 오므로, 건수가 null 인지 여부가 "이 API 는
 * 온도를 안 준다"를 가르는 유일한 신호다. {@code int} 였다면 온도를 싣지 않은 응답도 건수 0 으로
 * 나가 "평가를 아직 못 받은 유저"와 똑같이 보인다. {@code participatedActivities} 의 null 과 빈
 * 배열도 같은 이유로 구분한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private Long id;
    private String email;
    private String schoolEmail;
    private boolean schoolVerified;
    private String name;
    private String school;
    private String campus;
    private String college;
    private String major;
    private String grade;
    private String interestJobPrimary;
    private String interestJobSecondary;
    private String interestJobTertiary;
    private String tagline;
    /** 사용자가 직접 쓴 포트폴리오 서술. 아직 안 썼으면 null. */
    private String portfolio;
    /** 프로필 사진 공개 URL. 사진이 없거나 업로드가 아직 안 끝났으면 null. */
    private String profileImageUrl;

    /**
     * 협업 온도. 평가 건수와 무관하게 항상 값이 있고, 0건이면 기준점 36.5 다.
     *
     * <p>온도를 싣지 않는 경로에서만 null 이다. 구분은 {@link #collaborationReviewCount} 로 한다
     * (클래스 주석 참고).
     */
    private BigDecimal collaborationTemperature;
    /** 받은 평가 건수. 온도를 싣지 않는 응답에서는 0 이 아니라 null 이다 (클래스 주석 참고). */
    private Integer collaborationReviewCount;

    /**
     * 참여했던 활동 이력. 참여한 팀이 없으면 빈 배열이고, <b>이 값을 싣지 않는 응답에서는 null</b>
     * 이다 — 협업 온도와 같은 구분이다 (클래스 주석 참고).
     *
     * <p>{@code /mypage} 와 {@code /api/users/{userId}} 가 쓰는 것과 같은 타입을 그대로 쓴다.
     * 세 응답의 활동 항목이 프론트에서 같은 컴포넌트로 렌더링되므로 키 구성이 갈릴 이유가 없다.
     */
    private List<MyPageResponseDTO.ActivitySummaryDTO> participatedActivities;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 유저 엔티티만으로 조립하는 응답용. 지원서 응답의 {@code applicant} 처럼 온도·활동을 읽지 않는
     * 경로가 쓴다.
     */
    public static UserResponse from(User user) {
        // 건수·활동까지 null 로 둬야 "안 싣는 응답"이 "평가 0 건"/"참여 활동 없음"과 구분된다.
        return baseOf(user).build();
    }

    /**
     * 마이페이지 화면용 전체 조립 ({@code GET /api/users/me}, {@code PUT /api/users/me}).
     *
     * <p>얇은 {@link #from(User)} 와 갈라 둔 이유는 지원서 응답 쪽 제약이다 — 거기서는 엔티티만
     * 들고 있어 온도·활동을 읽을 수 없고, 지원자마다 활동을 읽으면 목록 조회가 N+1 이 된다.
     *
     * @param score      협업 온도 집계. 평가를 한 번도 안 받은 유저는 행 자체가 없어 null 이다 —
     *                   에러가 아니며, 기준점 온도 + 건수 0 으로 내려간다.
     * @param activities 참여했던 활동 이력. 참여한 팀이 없으면 빈 리스트다 (null 이 아니다).
     */
    public static UserResponse from(User user,
                                    UserCollaborationScore score,
                                    List<MyPageResponseDTO.ActivitySummaryDTO> activities) {
        return baseOf(user)
                .collaborationTemperature(score != null
                        ? score.getTemperature()
                        : CollaborationTemperatureCalculator.INITIAL)
                .collaborationReviewCount(score != null ? score.getReviewCount() : 0)
                .participatedActivities(activities)
                .build();
    }

    private static UserResponseBuilder baseOf(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .schoolEmail(user.getSchoolEmail())
                .schoolVerified(user.isSchoolVerified())
                .name(user.getName())
                .school(user.getSchool())
                .campus(user.getCampus())
                .college(user.getCollege())
                .major(user.getMajor())
                .grade(user.getGrade())
                .interestJobPrimary(user.getInterestJobPrimary())
                .interestJobSecondary(user.getInterestJobSecondary())
                .interestJobTertiary(user.getInterestJobTertiary())
                .tagline(user.getTagline())
                .portfolio(user.getPortfolio())
                .profileImageUrl(user.getProfileImageUrl())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt());
    }
}


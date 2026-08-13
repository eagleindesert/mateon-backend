package com.example.mateon.teams.dto.response;

import com.example.mateon.teams.domain.ApplicationStatus;
import com.example.mateon.teams.domain.TeamApplication;
import com.example.mateon.user.dto.UserResponse;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class TeamApplicationResponseDTO {
    private Long applicationId;
    private Long teamId;
    private String teamTitle;
    /**
     * 지원자 정보 (이름, 전공, 학년 등 포함).
     *
     * <p>[알려진 부채] {@link UserResponse} 는 email·schoolEmail 을 담는 "내 프로필" 전용
     * DTO라, 팀장이 지원서 목록을 열면 지원자의 계정 이메일이 함께 나간다. 팀 도메인의 다른
     * 응답({@link TeamOfferResponseDTO}, {@code UserRecommendationResponseDTO})은 연락처
     * 성격의 값을 담지 않는데 여기만 예외다.
     *
     * <p>좁히려면 email·schoolEmail 뿐 아니라 interestJob 3종과 createdAt/updatedAt 까지
     * 키 구성이 달라져 프론트 계약이 깨진다. 그래서 프론트와 합의하기 전까지는 그대로 둔다.
     * 좁힐 때는 이 필드를 얇은 요약 DTO 로 바꾸고, 상세는 지원자 id 로
     * {@code GET /api/users/{userId}} 를 부르게 하면 된다.
     */
    private UserResponse applicant;
    private String introduction;
    private String message;
    private String contactNumber;
    private String portfolioUrl;
    private boolean isMine;
    private ApplicationStatus status;
    private LocalDateTime createdAt;

    public static TeamApplicationResponseDTO from(TeamApplication application, Long currentUserId) {
        return TeamApplicationResponseDTO.builder()
                .applicationId(application.getId())
                .teamId(application.getTeam().getId())
                .teamTitle(application.getTeam().getTitle())
                .applicant(UserResponse.ofBasic(application.getApplicant()))
                .isMine(application.getApplicant().getId().equals(currentUserId))
                .introduction(application.getIntroduction())
                .message(application.getMessage())
                .contactNumber(application.getContactNumber())
                .portfolioUrl(application.getPortfolioUrl())
                .status(application.getStatus())
                .createdAt(application.getCreatedAt())
                .build();
    }
}

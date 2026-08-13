package com.example.mateon.teams.dto.response;

import com.example.mateon.teams.domain.ApplicationStatus;
import com.example.mateon.teams.domain.TeamApplication;
import com.example.mateon.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Schema(description = "지원서 1건. 지원자 화면과 팀장 화면이 같은 DTO 를 쓰고 isMine 으로 갈린다.")
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
    @Schema(description = "지원자 정보. 협업 온도·참여 활동은 **이 응답에서는 항상 null** 이다 "
            + "(내 프로필 DTO 를 재사용하는 자리라 그렇다). 필요하면 GET /api/users/{userId} 를 부른다.")
    private UserResponse applicant;
    private String introduction;
    private String message;
    private String contactNumber;
    private String portfolioUrl;
    @Schema(description = "조회자가 이 지원서의 작성자인지. **JSON 키는 `mine`** 이다.")
    private boolean isMine;
    @Schema(description = "지원 상태. 팀장이 처리하기 전에는 PENDING 이다.",
            allowableValues = {"PENDING", "APPROVED", "REJECTED"})
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

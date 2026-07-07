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
    private UserResponse applicant; // 지원자 정보 (이름, 전공, 학년 등 포함)
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
                .applicant(UserResponse.from(application.getApplicant()))
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
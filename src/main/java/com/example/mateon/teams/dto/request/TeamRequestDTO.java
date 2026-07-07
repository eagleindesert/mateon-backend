package com.example.mateon.teams.dto.request;

import com.example.mateon.teams.domain.Team;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.util.List;

@Getter @Setter
public class TeamRequestDTO {
    // 자율 프로젝트의 경우 eventId가 null
    private Long eventId;

    @NotBlank(message = "모집글 제목은 필수입니다.")
    private String title;

    private String promotionText;

    @NotEmpty(message = "모집 역할은 필수입니다.")
    private List<String> role;

    private String characteristic;

    @Min(value = 1, message = "모집 인원은 최소 1명 이상이어야 합니다.")
    private Integer capacity;

    @NotNull(message = "모집 시작일은 필수입니다.")
    private LocalDate recruitmentStartDate;

    @NotNull(message = "모집 종료일은 필수입니다.")
    private LocalDate recruitmentEndDate;

    public Team toEntity(Long leaderUserId) {
        Team team = new Team();
        team.setEventId(this.eventId);
        team.setTitle(this.title);
        team.setCapacity(this.capacity);
        team.setPromotionText(this.promotionText);
        team.setRole(this.role);
        team.setCharacteristic(this.characteristic);
        team.setRecruitmentStartDate(this.recruitmentStartDate);
        team.setRecruitmentEndDate(this.recruitmentEndDate);
        team.setLeaderUserId(leaderUserId);
        return team;
    }
}
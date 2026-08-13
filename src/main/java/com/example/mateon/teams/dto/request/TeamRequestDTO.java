package com.example.mateon.teams.dto.request;

import com.example.mateon.teams.domain.Team;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "팀 모집글 작성·수정 요청. 작성과 수정이 같은 형식이다.")
@Getter @Setter
public class TeamRequestDTO {
    // 자율 프로젝트의 경우 eventId가 null
    @Schema(description = "연결할 활동. **생략하면 '자율' 팀**이 되며, 활동 검색의 category=\"자율\" 로 잡힌다.")
    private Long eventId;

    @NotBlank(message = "모집글 제목은 필수입니다.")
    private String title;

    @Schema(description = "모집글 본문(홍보 문구). AI 임베딩 계산에 쓰여 추천 정확도에 영향을 준다.")
    private String promotionText;

    @Schema(description = "모집하는 역할 목록. 자유 문자열이며 최소 한 개는 있어야 한다.",
            example = "[\"백엔드\", \"디자이너\"]")
    @NotEmpty(message = "모집 역할은 필수입니다.")
    private List<String> role;

    @Schema(description = "팀 성향·분위기 소개. promotionText 와 함께 임베딩에 쓰인다.")
    private String characteristic;

    // 요구 기술 스택 (optional — 미전송/빈 배열 허용, 팀 임베딩 계산에 사용)
    @Schema(description = "요구 기술 스택. 생략하거나 빈 배열이어도 된다.", example = "[\"Spring\", \"React\"]")
    private List<String> requiredSkills;

    @Schema(description = "팀장을 포함한 총 정원. 승인·수락으로 이 수가 차면 모집이 자동 마감된다.")
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
        team.setRequiredSkills(this.requiredSkills);
        team.setRecruitmentStartDate(this.recruitmentStartDate);
        team.setRecruitmentEndDate(this.recruitmentEndDate);
        team.setLeaderUserId(leaderUserId);
        return team;
    }
}
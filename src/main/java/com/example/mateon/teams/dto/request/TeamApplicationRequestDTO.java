package com.example.mateon.teams.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "팀 지원서. 지원과 지원서 수정이 같은 형식이다. "
        + "AI 초안이 필요하면 POST /api/matching/proposals/user-to-team 을 먼저 부른다.")
@Getter @Setter
public class TeamApplicationRequestDTO {

    @Schema(description = "간단 소개글. AI 초안의 summary 를 넣으면 된다.")
    private String introduction; // 간단 소개글

    @Schema(description = "지원 동기 본문. AI 초안의 message 를 넣으면 된다.")
    @NotBlank(message = "지원 동기는 필수입니다.")
    private String message;

    @Schema(description = "팀장이 볼 연락처. 승인 전에도 팀장에게는 보인다.")
    @NotBlank(message = "연락처는 필수입니다.")
    private String contactNumber;

    @Schema(description = "포트폴리오 링크(선택).")
    private String portfolioUrl; // 선택 사항
}
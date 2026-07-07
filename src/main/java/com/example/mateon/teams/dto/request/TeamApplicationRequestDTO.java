package com.example.mateon.teams.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class TeamApplicationRequestDTO {

    private String introduction; // 간단 소개글

    @NotBlank(message = "지원 동기는 필수입니다.")
    private String message;

    @NotBlank(message = "연락처는 필수입니다.")
    private String contactNumber;

    private String portfolioUrl; // 선택 사항
}
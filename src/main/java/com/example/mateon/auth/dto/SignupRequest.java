package com.example.mateon.auth.dto;

import com.example.mateon.user.domain.User;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequest {
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    // 도메인(.ac.kr) 검증은 AuthService 에서 처리한다. DTO 는 이메일 형식만 검사.
    private String email;

    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(min = 10, max = 20, message = "비밀번호는 10-20자리 이내로 입력해주세요.")
    private String password;

    @NotBlank(message = "비밀번호 확인을 입력해주세요.")
    private String passwordConfirm;

    @NotBlank(message = "이름을 입력해주세요.")
    @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
    private String name;

    // 캠퍼스는 선택사항 (화면에서 선택할 수 있음)
    private User.Campus campus;

    // 단과대학은 선택사항
    @Size(max = 100, message = "단과대는 100자 이하여야 합니다.")
    private String college;

    // 학과는 선택사항
    @Size(max = 100, message = "전공은 100자 이하여야 합니다.")
    private String major;

    // 학년은 선택사항
    @Size(max = 10, message = "학년은 10자 이하여야 합니다.")
    private String grade;

    // 희망직무는 선택사항
    @Size(max = 100, message = "희망직무는 100자 이하여야 합니다.")
    private String interestJobPrimary;

    @Size(max = 100, message = "희망직무는 100자 이하여야 합니다.")
    private String interestJobSecondary;

    @Size(max = 100, message = "희망직무는 100자 이하여야 합니다.")
    private String interestJobTertiary;

    // 한 줄 소개는 선택사항 (화면에도 "선택"으로 표시됨)
    @Size(max = 200, message = "태그라인은 200자 이하여야 합니다.")
    private String tagline;
}


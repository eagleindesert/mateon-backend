package com.example.mateon.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

// 로그인 후 학교(재학생) 이메일 인증코드 발송 요청.
@Getter
@Setter
public class SchoolEmailRequest {
    @NotBlank(message = "학교 이메일을 입력해주세요.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    private String schoolEmail;
}

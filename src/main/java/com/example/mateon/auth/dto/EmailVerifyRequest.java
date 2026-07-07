package com.example.mateon.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmailVerifyRequest {
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    private String email;

    @NotBlank(message = "인증코드를 입력해주세요.")
    @Pattern(regexp = "^\\d{6}$", message = "인증코드는 6자리 숫자여야 합니다.")
    private String code;
}


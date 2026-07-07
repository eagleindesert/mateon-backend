package com.example.mateon.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest {
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    private String email;

    @NotBlank(message = "현재 비밀번호를 입력해주세요.")
    private String currentPassword;

    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Size(min = 10, max = 20, message = "비밀번호는 10-20자리 이내로 입력해주세요.")
    private String newPassword;

    @NotBlank(message = "새 비밀번호 확인을 입력해주세요.")
    private String newPasswordConfirm;
}


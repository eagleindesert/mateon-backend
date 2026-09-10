package com.example.mateon.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "비밀번호 재설정 확인. 메일 링크의 token 과 새 비밀번호를 보낸다.")
@Getter
@Setter
public class PasswordResetConfirmRequest {

    @Schema(description = "메일 링크 쿼리의 token. 서버가 해시한 값과 대조한다.")
    @NotBlank(message = "재설정 토큰을 입력해주세요.")
    private String token;

    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Size(min = 10, max = 20, message = "비밀번호는 10-20자리 이내로 입력해주세요.")
    private String newPassword;

    @NotBlank(message = "새 비밀번호 확인을 입력해주세요.")
    private String newPasswordConfirm;
}

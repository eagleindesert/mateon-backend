package com.example.mateon.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

// 이메일 인증 성공 응답. 회원가입(/signup) 요청에 그대로 담아 보내야 하는 일회용 티켓을 반환한다.
@Schema(description = "이메일 인증 성공 응답")
@Getter
@AllArgsConstructor
public class EmailVerifyResponse {

    @Schema(description = "회원가입(POST /api/auth/signup) 요청에 그대로 담아 보낼 일회용 티켓. 30분간 유효하다.")
    private String verificationToken;
}

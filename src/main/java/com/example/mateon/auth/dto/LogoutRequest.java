package com.example.mateon.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "로그아웃 요청. refreshToken 이 있으면 그 세션만 끊고, "
  + "email 만 있으면 (deprecated) 전 세션을 끊는다.")
@Getter
@Setter
public class LogoutRequest {

    @Schema(description = "끊을 세션의 refreshToken. 있으면 이 값만 보고 email 은 무시한다. "
      + "이미 없는 토큰이어도 200 이다.")
    private String refreshToken;

    @Schema(description = "deprecated. refreshToken 없이 이 값만 있으면 그 유저의 모든 "
      + "세션을 끊는다. 웹은 refreshToken 을 보낸다.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    private String email;
}

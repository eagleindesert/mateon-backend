package com.example.mateon.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "로그인·회원가입·토큰 재발급의 공통 응답")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {

    @Schema(description = "API 호출에 쓰는 토큰. Authorization 헤더에 `Bearer {accessToken}` 으로 넣는다.")
    private String accessToken;
    @Schema(description = "accessToken 이 만료됐을 때 POST /api/auth/token/refresh 에 넘길 토큰. "
      + "재발급을 해도 이 값은 그대로이므로 다시 저장할 필요가 없다.")
    private String refreshToken;
    @Schema(description = "토큰 종류. 항상 Bearer 다.", example = "Bearer")
    private String tokenType;
    @Schema(description = "accessToken 의 남은 수명(초). 밀리초가 아니다.", example = "3600")
    private long expiresIn;
}

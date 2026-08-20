package com.example.mateon.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "카카오 로그인 요청")
@Getter
@Setter
public class KakaoLoginRequest {

    // RN 이 카카오 네이티브 SDK 로 로그인해 받은 액세스 토큰.
    @Schema(description = "앱이 카카오 네이티브 SDK 로 받은 **카카오** 액세스 토큰. 인가코드가 아니고, 우리 서버의 accessToken 도 아니다.")
    @NotBlank(message = "카카오 액세스 토큰을 입력해주세요.")
    private String accessToken;
}

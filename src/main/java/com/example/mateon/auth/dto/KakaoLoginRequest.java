package com.example.mateon.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class KakaoLoginRequest {
    // RN 이 카카오 네이티브 SDK 로 로그인해 받은 액세스 토큰.
    @NotBlank(message = "카카오 액세스 토큰을 입력해주세요.")
    private String accessToken;
}

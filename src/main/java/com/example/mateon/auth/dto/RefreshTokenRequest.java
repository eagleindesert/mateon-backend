package com.example.mateon.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RefreshTokenRequest {
    @NotBlank(message = "리프레시 토큰을 입력해주세요.")
    private String refreshToken;
}


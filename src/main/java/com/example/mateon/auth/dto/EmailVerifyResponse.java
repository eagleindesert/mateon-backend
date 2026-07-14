package com.example.mateon.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 이메일 인증 성공 응답. 회원가입(/signup) 요청에 그대로 담아 보내야 하는 일회용 티켓을 반환한다.
@Getter
@AllArgsConstructor
public class EmailVerifyResponse {
    private String verificationToken;
}

package com.example.mateon.auth.client;

/**
 * 카카오 /v2/user/me 응답에서 우리가 실제로 쓰는 값만 추린 경량 DTO.
 * providerId 는 카카오 회원번호(항상 존재), 나머지는 사용자 동의에 따라 없을 수 있다.
 */
public record KakaoUserInfo(
        String providerId,
        String email,
        boolean emailVerified,
        String nickname
) {
}

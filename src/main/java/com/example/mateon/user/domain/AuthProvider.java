package com.example.mateon.user.domain;

/**
 * 유저의 가입 경로(인증 제공자).
 * LOCAL 은 이메일/비밀번호 자체 가입, 나머지는 소셜 로그인.
 */
public enum AuthProvider {
    LOCAL, KAKAO, GOOGLE, NAVER, APPLE
}

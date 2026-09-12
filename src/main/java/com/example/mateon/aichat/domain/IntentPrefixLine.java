package com.example.mateon.aichat.domain;

/**
 * 매칭 의도 추출에 실을 접두 한 줄. {@link AiChatMessage} 로 저장될 때의 kind 와 본문.
 *
 * @param kind PROFILE 또는 PORTFOLIO
 * @param content 명세 라벨이 붙은 전문
 */
public record IntentPrefixLine(IntentPrefixKind kind, String content) {
}

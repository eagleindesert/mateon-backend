package com.example.mateon.aichat.domain;

/**
 * 대화 한 줄의 발화 주체.
 *
 * <p>
 * USER 와 ASSISTANT 를 모두 저장하지만 도메인 AI 로 보내는 건 USER 뿐이다 — FastAPI
 * 명세상 messages 는 "사용자가 한 말"만 담는다. (matching 의 IntentMessageRole 을 대체한다.
 * 대화 로그가 도메인별로 흩어져 있을 이유가 없어 통합 로그로 옮겼다)
 */
public enum AiChatRole {
    USER,
    ASSISTANT
}

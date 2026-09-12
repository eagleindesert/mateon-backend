package com.example.mateon.aichat.domain;

/**
 * 대화 한 줄의 발화 주체.
 *
 * <p>
 * USER 와 ASSISTANT 를 모두 저장하고, 매칭 의도 추출 요청에도 둘 다 실는다 — FastAPI
 * 명세(2026-07-15)가 짧은 답을 직전 질문에 붙이려면 assistant 발화가 필요하다고 못 박았다.
 */
public enum AiChatRole {
    USER,
    ASSISTANT;

    /**
     * FastAPI messages[].role. 명세가 소문자 "user"/"assistant" 를 요구한다.
     */
    public String toExtractRole() {
        return this == USER ? "user" : "assistant";
    }
}

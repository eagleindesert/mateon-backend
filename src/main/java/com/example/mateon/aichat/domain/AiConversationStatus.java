package com.example.mateon.aichat.domain;

/**
 * AI 대화 스레드의 상태.
 *
 * <p>사용자당 ACTIVE 는 최대 1건이다 — V30 의 부분 유니크 인덱스(uk_ai_conversations_active)가
 * DB 레벨에서 보장한다.
 */
public enum AiConversationStatus {

    /** 진행 중. 새 발화는 이 대화에 이어 붙는다. */
    ACTIVE,

    /** 종료됨. 다음 발화는 새 대화를 만든다. */
    CLOSED
}

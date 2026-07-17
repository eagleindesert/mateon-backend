package com.example.mateon.matching.domain;

/**
 * 대화 이력의 발화 주체.
 * 저장은 둘 다 하지만, FastAPI 로는 USER 만 보낸다
 * (명세상 messages 는 "사용자가 한 말"만 담는다).
 */
public enum IntentMessageRole {
    USER,
    ASSISTANT
}

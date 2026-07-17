package com.example.mateon.matching.client;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * FastAPI POST /intents/extract 요청 본문.
 *
 * <pre>
 * { "messages": [ { "id": 1, "message": "..." }, { "id": 2, "message": "..." } ] }
 * </pre>
 *
 * 필드명이 전부 단일 단어라 snake_case 변환이 필요 없다 (응답과 달리 @JsonProperty 불필요).
 */
@Getter
@AllArgsConstructor
public class IntentExtractRequest {

    private final List<Message> messages;

    @Getter
    @AllArgsConstructor
    public static class Message {
        /** 1부터 순서대로 증가. 배열 순서가 곧 대화 순서. */
        private final int id;
        private final String message;
    }
}

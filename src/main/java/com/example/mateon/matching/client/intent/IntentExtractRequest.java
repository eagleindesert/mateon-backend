package com.example.mateon.matching.client.intent;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * FastAPI POST /intents/extract 요청 본문.
 *
 * <pre>
 * { "messages": [ { "id": 1, "role": "user", "message": "..." }, ... ] }
 * </pre>
 *
 * {@code role} 은 {@code "user"} 또는 {@code "assistant"}. 필드명이 전부 단일 단어라
 * snake_case 변환이 필요 없다.
 */
@Getter
@AllArgsConstructor
public class IntentExtractRequest {

    private final List<Message> messages;

    /**
     * 테스트·스텁 호출용. 모두 role=user. id 는 클라이언트가 다시 매긴다.
     */
    public static List<Message> users(String... texts) {
        List<Message> messages = new ArrayList<>(texts.length);
        for (String text : texts) {
            messages.add(Message.user(text));
        }
        return messages;
    }

    @Getter
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Message {
        /** 1부터 순서대로 증가. 배열 순서가 곧 대화 순서. */
        private final int id;
        /** FastAPI 명세 소문자. {@code "user"} 또는 {@code "assistant"}. */
        private final String role;
        private final String message;

        /**
         * id 는 보내기 직전 클라이언트가 다시 매긴다.
         */
        public static Message of(String role, String text) {
            return new Message(0, role, text);
        }

        public static Message user(String text) {
            return of("user", text);
        }

        public static Message assistant(String text) {
            return of("assistant", text);
        }
    }
}

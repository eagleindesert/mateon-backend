package com.example.mateon.aigateway.client;

import com.example.mateon.aichat.domain.RoutableDomain;

/**
 * 라우터 LLM 의 판정 결과. Spring AI 의 BeanOutputConverter 가 이 record 로 JSON 스키마를 만들어
 * 프롬프트에 붙이고, 응답을 다시 이 타입으로 역직렬화한다.
 *
 * <p>
 * 필드를 늘리거나 이름을 바꾸면 LLM 에게 보내는 스키마가 함께 바뀐다 — 프롬프트를 따로
 * 고칠 필요가 없는 대신, 여기를 건드리는 건 곧 프롬프트를 건드리는 것이다.
 *
 * @param domain 판정된 도메인. enum 밖의 값이 오면 역직렬화가 실패하고
 * {@link AiRouterClient} 가 폴백한다.
 * @param assistantMessage 위임할 곳이 없는 판정(UNCLEAR/OUT_OF_SCOPE)일 때 사용자에게 보여줄
 * 한국어 문구. 위임되는 판정이면 도메인 AI 가 답하므로 비어 있어도 된다.
 */
public record RouteDecision(RoutableDomain domain, String assistantMessage) {

    /**
     * 라우터를 못 쓰거나 건너뛸 때의 기본 판정. 도입 전 동작(무조건 매칭)과 같다.
     */
    public static RouteDecision passThrough() {
        return new RouteDecision(RoutableDomain.MATCHING_INTENT, null);
    }
}

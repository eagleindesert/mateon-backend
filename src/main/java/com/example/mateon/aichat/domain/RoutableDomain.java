package com.example.mateon.aichat.domain;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * AI 게이트웨이가 고를 수 있는 도메인 카탈로그. 라우터의 판정 결과이자, 메시지 한 줄이 어느
 * 도메인 소관인지 표시하는 값이다.
 *
 * <p><b>왜 aigateway 가 아니라 여기 있나</b> — 이 값이 ai_conversation_messages.domain 컬럼에
 * 저장되고, 도메인 서비스(matching)가 자기 발화를 골라 읽을 때도 쓴다. aichat 이 aigateway 를
 * 의존하면 순환이 되므로 통합 로그 쪽이 소유한다. aigateway 는 여기를 가져다 쓴다.
 *
 * <p><b>도메인을 추가할 때는 여기 한 줄만 늘리면 된다.</b> 라우터의 시스템 프롬프트도
 * ({@link #catalogForPrompt()}), 응답 스키마도 이 enum 에서 나온다. 프롬프트를 따로 고칠 필요가
 * 없다는 게 이 구조의 요점이다 — 카탈로그와 프롬프트가 어긋나는 사고를 원천에서 막는다.
 *
 * <p>{@link #endpoint} 가 null 인 상수는 위임할 곳이 없는 판정이다(되묻기/범위 밖). 이들은
 * 메시지 컬럼에 저장되지 않고 domain 이 null 로 남는다 — 게이트웨이가 혼자 답한 턴이라는 뜻이다.
 */
public enum RoutableDomain {

    MATCHING_INTENT("/api/matching/intents/messages",
            "팀원이나 팀을 찾는 이야기, 또는 매칭 조건(맡고 싶은 역할, 다룰 수 있는 기술, "
            + "관심 분야, 활동 목표, 협업 스타일, 경험 수준)을 정하는 대화"),

    UNCLEAR(null,
            "무엇을 원하는지 아직 알 수 없어 한 번 더 물어봐야 하는 경우. "
            + "인사말이나 '도와줘' 처럼 내용이 없는 발화가 여기 해당한다"),

    OUT_OF_SCOPE(null,
            "이 서비스가 다루지 않는 주제. 날씨, 일반 상식, 코딩 질문, 잡담 등");

    /** 이 도메인의 대화 엔드포인트. 위임할 곳이 없는 판정이면 null. */
    private final String endpoint;

    /** 라우터 프롬프트에 그대로 실리는 설명. LLM 이 읽는 문장이므로 구체적으로 쓴다. */
    private final String description;

    RoutableDomain(String endpoint, String description) {
        this.endpoint = endpoint;
        this.description = description;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getDescription() {
        return description;
    }

    /** 위임할 도메인 서비스가 있는가. false 면 게이트웨이가 직접 문구로 답한다. */
    public boolean isDelegatable() {
        return endpoint != null;
    }

    /** 라우터 시스템 프롬프트에 넣을 카탈로그 문단. 상수를 추가하면 자동으로 따라온다. */
    public static String catalogForPrompt() {
        return Arrays.stream(values())
                .map(d -> "- " + d.name() + ": " + d.description)
                .collect(Collectors.joining("\n"));
    }
}

package com.example.mateon.events.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * FastAPI POST /internal/contests/embedding:refresh 요청 본문.
 *
 * <p>
 * AI 서버는 stateless 로 계산만 한다. event_id 는 어느 요청의 결과인지 BE 가 구분하려고
 * 보내는 echo 용이고, AI 는 해석하지 않는다.
 */
@Getter
@AllArgsConstructor
public class ContestEmbeddingRefreshRequest {

    @JsonProperty("event_id")
    private final Long eventId;

    private final String title;

    private final String description;
}

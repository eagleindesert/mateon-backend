package com.example.mateon.events.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * FastAPI POST /internal/contests/embedding:refresh 응답 본문.
 *
 * <p>
 * @JsonNaming 을 쓰지 않고 @JsonProperty 만 쓰는 이유, @JsonIgnoreProperties 를 클래스에
 * 명시하는 이유는 {@link com.example.mateon.matching.client.intent.IntentExtractResponse} 참조
 * (Jackson 2/3 공존 + aiRestTemplate 자체 컨버터).
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContestEmbeddingRefreshResponse {

    /**
     * 요청으로 보낸 event_id 의 echo. 저장 키는 우리가 아는 eventId 이지 이 값이 아니다.
     */
    @JsonProperty("event_id")
    private Long eventId;

    /**
     * 1536 차원 임베딩. 스펙이 double 배열이라 double[] 로 받고,
     * float[] 변환은 차원 검증과 함께 저장부에서 명시적으로 한다.
     */
    @JsonProperty("embedding_vector")
    private double[] embeddingVector;
}

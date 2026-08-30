package com.example.mateon.events.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * FastAPI POST /contests/similarity-map 요청 본문.
 *
 * <p>
 * AI 서버는 후보를 조회하거나 임베딩을 만들지 않는다. 기준 공모전과 후보의 벡터를
 * 백엔드가 전부 실어 보낸다.
 *
 * <p>
 * 벡터를 float[] 로 보내는 이유: DB(pgvector)가 float4 로 갖고 있는 값이라 double 로 넓히면
 * 0.01 이 0.009999999776 처럼 늘어져 페이로드만 커진다.
 */
@Getter
@AllArgsConstructor
public class ContestSimilarityMapRequest {

    private final ContestItem query;

    private final List<ContestItem> candidates;

    @JsonProperty("top_n")
    private final Integer topN;

    @Getter
    @AllArgsConstructor
    public static class ContestItem {

        /**
         * 우리 events.id 의 문자열. AI 는 의미를 해석하지 않고 응답에 그대로 돌려준다.
         */
        private final String id;

        @JsonProperty("embedding_vector")
        private final float[] embeddingVector;

        private final String title;

        private final String organizer;

        private final String category;

        private final String field;

        @JsonProperty("detail_url")
        private final String detailUrl;
    }
}

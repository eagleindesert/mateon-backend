package com.example.mateon.matching.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * FastAPI 추천 응답 본문. 두 방향(user-to-team / team-to-user)이 같은 스키마를 쓴다 —
 * 엔드포인트만 다르고 응답 모양은 동일하다는 게 AI 명세다.
 *
 * <p>@JsonNaming 을 쓰지 않고 @JsonProperty 만 쓰는 이유, @JsonIgnoreProperties 를 클래스에
 * 명시하는 이유는 {@link IntentExtractResponse} 참조.
 *
 * <p>label 은 AI 가 가장 점수가 높은 구성요소를 실제 매칭된 값으로 채워 만든 문장이다
 * ("BE 역할을 모집하고 있어요"). 백엔드는 해석하지 않고 그대로 프론트에 내려준다 — 문구 생성은
 * 의도 추출의 assistant_message 와 마찬가지로 전부 AI 몫이다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecommendationResponse {

    private List<Recommendation> recommendations;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Recommendation {

        /**
         * 우리가 보낸 candidate_id. 방향에 따라 teamId(user-to-team) 또는 userId(team-to-user)다.
         * 보낸 적 없는 값이 오면 호출자가 무시한다.
         */
        @JsonProperty("candidate_id")
        private Long candidateId;

        private Double score;

        /** 추천 근거 문구. */
        private String label;
    }
}

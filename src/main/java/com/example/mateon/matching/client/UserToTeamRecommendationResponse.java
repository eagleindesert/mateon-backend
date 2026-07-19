package com.example.mateon.matching.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * FastAPI POST /recommendations/user-to-team 응답 본문.
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
public class UserToTeamRecommendationResponse {

    private List<Recommendation> recommendations;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Recommendation {

        /** 우리가 보낸 candidate_id(= teamId). 보낸 적 없는 값이 오면 호출자가 무시한다. */
        @JsonProperty("candidate_id")
        private Long candidateId;

        private Double score;

        /** 추천 근거 문구. */
        private String label;
    }
}

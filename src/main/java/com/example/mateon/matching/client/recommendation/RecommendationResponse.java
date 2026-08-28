package com.example.mateon.matching.client.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.JsonNode;

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

        /**
         * 컴포넌트별 점수 (similarity/role_match/deficit_fit/...). 선택 피드백에 그대로 되돌려
         * 주려고 받는 값이라 <b>백엔드는 내용을 읽지 않는다</b>.
         *
         * <p>
         * Map&lt;String, Double&gt; 이 아니라 JsonNode 인 게 의도다. 명세가 "현재 위 6개 키를
         * 반환합니다"라고 써 키 집합이 늘 수 있음을 전제하는데, 타입을 못박으면 AI 가 키를
         * 추가하거나 값 타입을 바꾼 순간 <b>추천 응답 전체가 파싱 실패</b>한다. 랭킹에 쓰지도
         * 않는 분석용 필드 때문에 추천이 죽는 건 말이 안 된다. JsonNode 는 어떤 유효 JSON 도 받는다.
         *
         * <p>
         * 저장도 이 노드의 문자열 표현 그대로 한다 — 명세가 요구하는 "값을 재계산하거나 이름을
         * 바꾸지 않고 보관"이 그래야 지켜진다.
         *
         * <p>
         * <b>{@code tools.jackson}(Jackson 3) 의 JsonNode 여야 한다.</b> 이 프로젝트는 Jackson 2 와
         * 3 이 함께 클래스패스에 있는데, {@code aiRestTemplate} 에서 실제로 이기는 컨버터는
         * Jackson 3 이다. 여기에 {@code com.fasterxml.jackson.databind.JsonNode}(Jackson 2)를 쓰면
         * Jackson 3 이 그 클래스를 노드가 아닌 평범한 추상 클래스로 보고
         * "Type definition error" 로 실패하는데, 그 실패가 <b>추천 응답 전체</b>를 무너뜨린다 —
         * 위 문단이 막으려던 바로 그 사고가 타입 선택으로 일어난다.
         *
         * <p>
         * 애노테이션과 사정이 다르다는 점에 주의. {@code @JsonProperty} 는 jackson-annotations
         * 한 벌을 양쪽이 공유해서 어느 컨버터가 이기든 동작하지만, JsonNode 는 공유 타입이
         * 없어 한쪽을 골라야 한다. {@code RecommendationLogService} 의 import 도 같이 맞춘다.
         */
        @JsonProperty("component_scores")
        private JsonNode componentScores;
    }
}

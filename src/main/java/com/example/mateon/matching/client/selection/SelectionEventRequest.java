package com.example.mateon.matching.client.selection;

import com.example.mateon.matching.domain.SelectionDirection;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * FastAPI POST /selection-events 요청 본문.
 *
 * <p>
 * AI 서버는 stateless 라 "무엇을 보여줬을 때 무엇을 골랐는지"를 스스로 알 수 없다. 그래서
 * 선택된 후보뿐 아니라 <b>선택 당시 화면에 노출된 목록 전체</b>를 여기 실어 보낸다 — 선택 대비
 * 분석(고른 것 vs 안 고른 것)이 이 목록 위에서 이뤄지기 때문이다.
 *
 * <p>
 * 제안 조립(/proposals/*)과는 독립된 요청이다. 사용자 응답 흐름을 막지 않도록 지원/제안 발송이
 * 커밋된 뒤 별도 스레드에서 나간다.
 */
@Getter
@AllArgsConstructor
public class SelectionEventRequest {

    /** enum 상수 이름이 명세의 값과 같아 Jackson 기본 직렬화가 그대로 맞는다. */
    private final SelectionDirection direction;

    @JsonProperty("selected_candidate_id")
    private final Long selectedCandidateId;

    @JsonProperty("selection_context")
    private final SelectionContext selectionContext;

    @Getter
    @AllArgsConstructor
    public static class SelectionContext {

        /**
         * 이 선택 이벤트 전용 멱등키. 지원/제안 id 에서 결정적으로 파생시킨다
         * (조립 규칙은 {@link com.example.mateon.matching.service.SelectionEventService}).
         */
        @JsonProperty("idempotency_key")
        private final String idempotencyKey;

        /**
         * 선택 주체의 클러스터 계산용 원본 필드. 키가 방향마다 다르고 값 타입도 섞여 있어
         * (리스트/문자열) Map 으로 둔다. 조립은 조회 단계가 한다.
         */
        @JsonProperty("chooser_fields")
        private final Map<String, Object> chooserFields;

        @JsonProperty("shown_candidates")
        private final List<ShownCandidate> shownCandidates;
    }

    @Getter
    @AllArgsConstructor
    public static class ShownCandidate {

        @JsonProperty("candidate_id")
        private final Long candidateId;

        /** 추천 응답의 score 를 그대로 넣는다. */
        @JsonProperty("total_score")
        private final double totalScore;

        /**
         * 추천 응답의 component_scores 를 <b>그대로</b> 넣는다.
         *
         * <p>
         * 타입이 Map 이 아니라 String + {@link JsonRawValue} 인 게 의도다. 저장돼 있던 원문
         * JSON 을 파싱 없이 본문에 그대로 박아, 명세가 요구하는 "값을 재계산하거나 이름을 바꾸지
         * 않는다"를 문자 단위로 지킨다. Map 으로 한 번 풀었다 담으면 그 보장이 깨진다.
         *
         * <p>
         * null 이면 JSON 에 {@code null} 로 나간다 — 추천 당시 AI 가 component_scores 를 주지
         * 않았다는 뜻이고, 우리가 지어낼 수 있는 값이 아니다.
         */
        @JsonRawValue
        @JsonProperty("component_scores")
        private final String componentScores;
    }
}

package com.example.mateon.matching.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * FastAPI POST /recommendations/reason 응답 본문.
 *
 * <p>reason 하나뿐이다. proposal_id 는 여기 없다 — 제안을 저장하며 백엔드가 채번한다.
 *
 * <p>@JsonNaming 을 쓰지 않는 이유, @JsonIgnoreProperties 를 클래스에 명시하는 이유는
 * {@link IntentExtractResponse} 참조.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecommendationReasonResponse {

    /** AI 가 생성한 추천 상세 이유. 백엔드는 해석하지 않고 그대로 내려준다. */
    private String reason;
}

package com.example.mateon.matching.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * FastAPI 의 ProposalSchema — /proposals/user-to-team · /proposals/team-to-user 공통 응답.
 *
 * <p>실제로 읽는 건 세 개뿐이다. 나머지(user_id, team_id, contest_id, sender_id, receiver_id,
 * intent_id, direction)는 우리가 보낸 값을 그대로 돌려받는 에코라 다시 읽을 이유가 없다 —
 * <b>우리가 어느 엔드포인트를 호출했는지가 더 신뢰할 수 있는 출처다.</b>
 *
 * <p>portfolio_role_fit_score 도 매핑하지 않는다. 명세상 필드만 예약돼 있고 항상 null 이다
 * (포트폴리오 데이터 소스가 없어 계산에서 빠졌다). ignoreUnknown 이 알아서 흘려보낸다.
 *
 * <p>@JsonNaming 을 쓰지 않는 이유는 {@link IntentExtractResponse} 참조.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProposalResponse {

    /** 제안 한 줄 요약. 지원서의 introduction 자리에 들어갈 초안이다. */
    private String summary;

    /** 제안 본문. 지원 동기/제안 메시지 자리에 들어갈 초안이다. */
    private String message;

    /**
     * 우리가 보낸 값이 그대로 돌아온다. 되읽는 유일한 에코 필드인데, 응답에 실어 주는 값이라
     * "AI 가 실제로 받은 점수"를 그대로 보여 주는 편이 진단에 낫기 때문이다.
     */
    @JsonProperty("synergy_score")
    private Double synergyScore;
}

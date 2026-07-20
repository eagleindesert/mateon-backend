package com.example.mateon.matching.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * FastAPI POST /proposals/user-to-team · /proposals/team-to-user 요청 본문.
 *
 * <p>두 방향이 같은 스키마를 쓴다. 요청에 direction 이 없고, 어느 경로로 부르느냐가 곧 방향이다.
 *
 * <p>앞쪽 여섯 개(user_id ~ intent_id)는 AI 가 계산에 쓰지 않는다 — 무상태 서버라 응답에 그대로
 * 되돌려 주기만 한다. 그래도 채워 보내는 이유는 AI 서버 로그에서 어느 쌍의 요청인지 추적할 수
 * 있어야 하기 때문이다.
 *
 * <p>실제로 문장을 만드는 재료는 뒤의 세 개다:
 * <ul>
 *   <li>synergy_score — 추천 단계에서 이미 계산된 값. <b>여기서 재계산하지 않는다</b>(AI 명세).</li>
 *   <li>candidate_summary / target_summary —
 *       {@link com.example.mateon.matching.service.RecommendationSummaryFactory} 가 조립한다.
 *       상세 이유(/recommendations/reason)와 같은 규칙으로 <b>후보 = 선택된 상대,
 *       대상 = 질의 주체</b> 자리에 넣는다.</li>
 * </ul>
 *
 * <p>@JsonNaming 은 쓰지 않는다 — 이유는 {@link IntentExtractResponse} 클래스 주석 참고
 * (Jackson 2/3 공존).
 */
@Getter
@AllArgsConstructor
public class ProposalAssemblyRequest {

    @JsonProperty("user_id")
    private final Long userId;

    @JsonProperty("team_id")
    private final Long teamId;

    /**
     * 팀이 참여 중인 활동. 자율 프로젝트 팀은 활동이 없어 <b>null 이 정상</b>이다
     * (teams.event_id 가 nullable).
     */
    @JsonProperty("contest_id")
    private final Long contestId;

    /** 제안을 보내는 쪽. 유저→팀이면 userId, 팀→유저면 teamId 다. */
    @JsonProperty("sender_id")
    private final Long senderId;

    /** 제안을 받는 쪽. senderId 와 반대 자리. */
    @JsonProperty("receiver_id")
    private final Long receiverId;

    /**
     * 매칭 의도 식별자 = matching_intent_slots.id.
     *
     * <p>세션 id 가 아니다. 슬롯은 사용자당 1건 upsert 라 "이 사람의 현재 의도"와 1:1 인 반면,
     * 세션은 대화를 다시 할 때마다 늘어난다. AI 가 이 값을 명목 전달만 하므로 안정적인 쪽을 쓴다.
     */
    @JsonProperty("intent_id")
    private final Long intentId;

    /** 추천 단계에서 나온 적합도(0~1). 추천 이력의 score 를 그대로 넘긴다. */
    @JsonProperty("synergy_score")
    private final Double synergyScore;

    /** 선택된 상대 쪽 요약. 예: "React/TypeScript 경험, 초보자" */
    @JsonProperty("candidate_summary")
    private final String candidateSummary;

    /** 질의 주체 쪽 요약. 예: "커머스 플랫폼, BE 1명 결핍" */
    @JsonProperty("target_summary")
    private final String targetSummary;
}

package com.example.mateon.matching.client.recommendation;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 룰 스코어링에 쓰이는 사용자 쪽 메타데이터. matching_intent_slots 에서 그대로 옮겨온 값이며,
 * 의도 추출(/intents/extract) 응답의 extracted 원본과 같은 것이다.
 *
 * <p>방향에 따라 자리가 바뀐다 — user-to-team 에서는 query_metadata,
 * team-to-user 에서는 candidates[].metadata 다.
 *
 * <p>이게 없으면 AI 는 임베딩 유사도만 계산할 수 있고 역할 일치도 같은 룰 점수를 못 낸다.
 */
@Getter
@AllArgsConstructor
public class UserMetadata {

    @JsonProperty("desired_roles")
    private final List<String> desiredRoles;

    private final List<String> skills;

    @JsonProperty("experience_level")
    private final String experienceLevel;

    @JsonProperty("activity_style")
    private final String activityStyle;

    /**
     * 활동 가능 시간대 (예: "평일 저녁"). <b>지금은 항상 null 이다.</b>
     *
     * <p>
     * 다른 필드와 달리 이건 AI 가 발화에서 추출해 주는 값이 아니라 <b>사용자가 직접 고르는
     * 값</b>이다. 아직 이 값을 받는 화면이 없어 채울 데이터가 없다 (matching_intent_slots 에
     * 컬럼도 없다). 필드를 미리 두는 이유는 AI 쪽 스키마가 이미 이 키를 기다리고 있어서다.
     *
     * <p>
     * 이 값이 비어도 추천 순위는 바뀌지 않는다 — 명세상 activity_time_match 는 총점에
     * 반영되지 않고 피드백 분석용으로만 반환된다.
     */
    @JsonProperty("activity_time")
    private final String activityTime;
}

package com.example.mateon.matching.client;

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
}

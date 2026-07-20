package com.example.mateon.matching.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 룰 스코어링에 쓰이는 팀 쪽 메타데이터.
 *
 * <p>방향에 따라 자리가 바뀐다 — user-to-team 에서는 candidates[].metadata,
 * team-to-user 에서는 query_metadata 다.
 *
 * <p>출처가 teams 테이블이 아니라 team_embeddings 인 게 중요하다. Team.role 은 한글 자유문자열
 * ("백엔드 개발자")이고 슬롯의 desiredRoles 는 AI 정규화 코드("BE")라 서로 매칭되지 않는다.
 * team_embeddings 의 메타데이터는 AI 가 같은 어휘로 정규화해 준 값이라 양쪽이 정렬된다.
 */
@Getter
@AllArgsConstructor
public class TeamMetadata {

    @JsonProperty("recruiting_roles")
    private final List<String> recruitingRoles;

    @JsonProperty("required_skills")
    private final List<String> requiredSkills;

    @JsonProperty("activity_style")
    private final String activityStyle;

    /** AI 가 팀 소개글에서 못 읽어냈으면 null → Boolean (primitive 금지). */
    @JsonProperty("beginner_friendly")
    private final Boolean beginnerFriendly;
}

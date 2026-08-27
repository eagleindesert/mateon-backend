package com.example.mateon.matching.client.recommendation;

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

    /**
     * 팀의 활동 가능 시간대. <b>지금은 항상 null 이다</b> — 이유는
     * {@link UserMetadata#getActivityTime()} 과 같다 (사용자 입력값인데 받는 화면이 없다).
     */
    @JsonProperty("activity_time")
    private final String activityTime;

    /**
     * 연결된 활동의 분야 코드 (예: "SCIENCE_ENGINEERING_TECH_IT"). 자율 프로젝트면 null.
     *
     * <p>
     * {@code Event.Field} 의 상수 이름이 AI 명세의 코드값과 그대로 일치해서 별도 매핑 없이
     * {@code name()} 을 쓴다. 한글 라벨(getLabel)이 아니다.
     *
     * <p>
     * <b>team-to-user 의 query_metadata 자리에서만 채운다.</b> 명세가 user-to-team 의
     * candidates[].metadata 에는 이 키를 적어 두지 않았다 — 같은 팀 객체인데 방향에 따라
     * 필드가 사라지는 게 의도인지 확인 전이라, 그 자리에는 null 을 보낸다. 데이터 자체는
     * 양쪽 다 있으므로 확인되면 호출부 한 줄로 채울 수 있다.
     */
    @JsonProperty("contest_field")
    private final String contestField;
}

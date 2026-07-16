package com.example.mateon.teams.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * FastAPI POST /internal/teams/embedding:refresh 요청 본문.
 *
 * <p>team_id 는 스펙상 요청/응답 어디에도 없다 — AI 서버는 stateless 로 계산만 하고,
 * 결과를 어느 팀 행에 저장할지는 백엔드가 안다.
 *
 * <p>recruiting_roles/required_skills/contest_field 는 백엔드가 이미 구조화된 값으로
 * 갖고 있어 그대로 보낸다. 활동 목표/스타일/강도/초보 환영 여부 등 자유 서술로만 표현되는
 * 값은 AI 가 intro_text 에서 직접 추출한다 (현재 팀 구성도 intro_text 에 자연어로 포함).
 */
@Getter
@AllArgsConstructor
public class TeamEmbeddingRefreshRequest {

    @JsonProperty("intro_text")
    private final String introText;

    @JsonProperty("recruiting_roles")
    private final List<String> recruitingRoles;

    @JsonProperty("required_skills")
    private final List<String> requiredSkills;

    /** 연결된 공모전/행사 분야. 자율 프로젝트(eventId null)면 null. */
    @JsonProperty("contest_field")
    private final String contestField;
}

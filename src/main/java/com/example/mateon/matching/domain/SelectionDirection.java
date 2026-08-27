package com.example.mateon.matching.domain;

/**
 * 추천/선택의 방향. AI 명세의 {@code direction} 값과 상수 이름이 그대로 일치한다
 * ({@code USER_TO_TEAM} / {@code TEAM_TO_USER}) — 그래서 직렬화에 별도 매핑이 필요 없다.
 *
 * <p>
 * 원래 {@code ProposalAssemblyService} 안에 private String 상수 두 개로 있던 것을 꺼냈다.
 * 선택 피드백(POST /selection-events)이 같은 두 값을 쓰게 되면서, 문자열이 두 곳에 복제되면
 * 한쪽 오타가 컴파일에 안 걸리고 AI 쪽 422 로만 드러나기 때문이다.
 *
 * <p>
 * 여기 있는 값은 AI 가 준 어휘가 아니라 <b>우리가 정한 방향</b>이라, 이 프로젝트가 AI 응답값에
 * enum 을 쓰지 않는 방침({@link MatchingIntentSlot} 주석)과 충돌하지 않는다.
 */
public enum SelectionDirection {

    /**
     * 유저가 팀을 고른다 (지원). 후보 id 는 teamId.
     */
    USER_TO_TEAM,

    /**
     * 팀이 유저를 고른다 (역제안). 후보 id 는 userId.
     */
    TEAM_TO_USER
}

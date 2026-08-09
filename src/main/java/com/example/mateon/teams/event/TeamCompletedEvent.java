package com.example.mateon.teams.event;

/**
 * 팀 활동이 종료됐다 (팀장 수동 종료 또는 공모전 마감일 경과 자동 종료).
 * 이 시점부터 협업 온도 평가가 열린다.
 *
 * @param autoCompleted 스케줄러가 마감일 경과로 닫았으면 true, 팀장이 직접 종료했으면 false.
 *                      혼자인 팀은 평가 요청을 보내지 않는데, 그러면 자동 종료된 팀의 팀장은
 *                      모집이 닫힌 사실조차 모르게 된다. 그 경우에만 따로 알리려고 구분한다
 *                      (수동 종료는 본인이 한 일이라 알림이 소음이다).
 */
public record TeamCompletedEvent(Long teamId, boolean autoCompleted) {
}

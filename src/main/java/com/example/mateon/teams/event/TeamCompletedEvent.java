package com.example.mateon.teams.event;

/**
 * 팀 활동이 종료됐다 (팀장 수동 종료 또는 공모전 마감일 경과 자동 종료).
 * 이 시점부터 협업 온도 평가가 열린다.
 */
public record TeamCompletedEvent(Long teamId) {
}

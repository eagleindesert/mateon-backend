package com.example.mateon.teams.domain;

/**
 * team_embeddings 행의 마지막 갱신 시도 결과.
 *
 * <p>행은 갱신을 시도했을 때만 생기므로 "아직 시도 안 함"(PENDING)은 없다. 시도 자체가 없었던 팀
 * (앱 재시작으로 @Async 큐가 유실된 경우 등)은 행이 아예 없는 것으로 구분된다.
 */
public enum TeamEmbeddingRefreshStatus {
    SUCCESS,    // 마지막 시도에서 임베딩 저장까지 완료
    FAILED      // 마지막 시도가 실패 (embedding 은 null 이거나 직전 성공 시점의 낡은 값)
}

package com.example.mateon.matching.event;

import com.example.mateon.matching.domain.SelectionDirection;

/**
 * 사용자가 추천 목록에서 고른 상대에게 실제로 지원/제안을 보냈다.
 *
 * <p>
 * 발행 지점이 "제안 문구 초안"이 아니라 <b>발송</b>인 게 중요하다. 초안 API(/proposals/*)는
 * 저장 없이 몇 번이든 다시 뽑는 경로라 그걸 선택으로 세면 같은 결정이 여러 건으로 기록된다.
 *
 * <p>
 * 추천을 거치지 않은 지원/제안도 이 이벤트를 발행한다 — "추천 이력이 있었나"는 여기서 판단할
 * 수 없고(teams 도메인은 추천 로그를 모른다), 수신 측이 이력을 못 찾으면 조용히 끝낸다.
 *
 * @param direction 선택 방향.
 * @param chooserId 선택 주체. USER_TO_TEAM 이면 userId, TEAM_TO_USER 면 teamId.
 * @param selectedCandidateId 선택된 상대. USER_TO_TEAM 이면 teamId, TEAM_TO_USER 면 userId.
 * @param referenceId 이 선택으로 생긴 지원서/제안의 id. 멱등키의 재료다 — 지원과 제안 모두
 * (팀, 상대) 쌍에 유니크 제약이 있어 같은 선택이면 항상 같은 값이 나온다.
 */
public record CandidateSelectedEvent(
  SelectionDirection direction,
  Long chooserId,
  Long selectedCandidateId,
  Long referenceId
) {

    public static CandidateSelectedEvent userToTeam(Long userId, Long teamId, Long applicationId) {
        return new CandidateSelectedEvent(SelectionDirection.USER_TO_TEAM, userId, teamId,
          applicationId);
    }

    public static CandidateSelectedEvent teamToUser(Long teamId, Long targetUserId, Long offerId) {
        return new CandidateSelectedEvent(SelectionDirection.TEAM_TO_USER, teamId, targetUserId,
          offerId);
    }
}

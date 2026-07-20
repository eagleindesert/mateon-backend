package com.example.mateon.matching.service;

import com.example.mateon.matching.client.RecommendationClient;
import com.example.mateon.matching.client.RecommendationReasonRequest;
import com.example.mateon.matching.dto.snapshot.ReasonSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.BiConsumer;

/**
 * 추천 상세 이유(lazy) 흐름의 오케스트레이터.
 *
 * <p>클래스 레벨 @Transactional 이 없는 게 여기서도 핵심이다 — {@link RecommendationService} /
 * {@link TeamToUserRecommendationService} 와 똑같은 이유다 (FastAPI read-timeout 이 60 초라
 * TX 안에서 호출하면 커넥션 풀이 마른다). 조회는 RecommendationQueryService(readOnly), 저장은
 * RecommendationLogService(@Transactional) 가 맡고 그 사이에서 AI 를 호출한다. 빈이 나뉜 것도
 * 필수다 — 같은 빈 안에서 호출하면 프록시를 타지 않아 @Transactional 이 무시된다.
 *
 * <p>다른 두 오케스트레이터와 다른 점은 <b>캐시 단계가 있다</b>는 것뿐이다. 이유는 LLM 이
 * 생성하는 긴 문장이라 느리고 비싼데, 같은 카드를 다시 여는 건 흔한 동작이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationReasonService {

    private final RecommendationQueryService queryService;
    private final RecommendationLogService logService;
    private final RecommendationClient client;

    /**
     * 유저가 추천받은 팀 하나에 대한 상세 이유.
     *
     * @param userId 요청자. 자기 추천 이력에서만 찾으므로 이 값이 곧 권한이다.
     */
    public String explainTeam(Long userId, Long teamId) {
        return explain(queryService.gatherReasonForUserToTeam(userId, teamId),
                logService::saveUserToTeamReason);
    }

    /**
     * 팀이 추천받은 유저 한 명에 대한 상세 이유 (역제안).
     *
     * @param leaderUserId 요청자. 팀장이 아니면 조회 단계에서 403 이 난다.
     */
    public String explainUser(Long teamId, Long targetUserId, Long leaderUserId) {
        return explain(queryService.gatherReasonForTeamToUser(teamId, targetUserId, leaderUserId),
                logService::saveTeamToUserReason);
    }

    /**
     * 두 방향이 공유하는 본체. 방향에 따라 다른 건 스냅샷을 어떻게 모으고 어디에 캐시하느냐뿐이라
     * 그 둘만 파라미터로 받는다 (AI 요청 스키마에 direction 이 없는 것과 같은 맥락이다).
     */
    private String explain(ReasonSnapshot snapshot, BiConsumer<Long, String> cacheWriter) {
        // ① 캐시 hit — AI 를 부르지 않는다.
        if (snapshot.hasCachedReason()) {
            return snapshot.getCachedReason();
        }

        // ② [TX 밖] FastAPI 호출. 수십 초가 걸려도 DB 커넥션을 잡고 있지 않다.
        String reason = client.reason(new RecommendationReasonRequest(
                snapshot.getCandidateSummary(),
                snapshot.getTargetSummary(),
                snapshot.getScoreContext()));

        // ③ [TX2] 캐시. 실패해도 이유는 이미 만들어졌으므로 응답을 막지 않는다
        //    (추천 결과 기록과 같은 규약 — 다음 조회 때 AI 를 한 번 더 부를 뿐이다).
        try {
            cacheWriter.accept(snapshot.getItemId(), reason);
        } catch (Exception e) {
            log.warn("상세 이유 캐시 실패 (응답에는 영향 없음). itemId={}", snapshot.getItemId(), e);
        }

        return reason;
    }
}

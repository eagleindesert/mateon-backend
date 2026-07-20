package com.example.mateon.matching.service;

import com.example.mateon.matching.client.RecommendationResponse.Recommendation;
import com.example.mateon.matching.domain.TeamToUserRecommendationLog;
import com.example.mateon.matching.domain.UserToTeamRecommendationLog;
import com.example.mateon.matching.repository.TeamToUserRecommendationLogRepository;
import com.example.mateon.matching.repository.UserToTeamRecommendationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 양방향 추천 결과를 기록한다. AI 호출이 끝난 뒤 별도 트랜잭션에서 돈다
 * (오케스트레이터가 TX 밖에서 AI 를 호출하므로 저장은 여기로 분리).
 *
 * <p>프론트에 내려가는 상위 N 건이 아니라 AI 가 점수를 매긴 결과 전체를 순위대로 남긴다 —
 * "상위 N 을 몇으로 잡는 게 맞나"를 나중에 데이터로 따지려면 잘린 뒤가 아니라 잘리기 전이 필요하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RecommendationLogService {

    private final UserToTeamRecommendationLogRepository logRepository;
    private final TeamToUserRecommendationLogRepository teamToUserLogRepository;

    /** @param ranked 점수 내림차순으로 이미 정렬된 결과. */
    public void save(Long userId, Long eventId, int candidateCount, List<Recommendation> ranked) {
        UserToTeamRecommendationLog entity =
                new UserToTeamRecommendationLog(userId, eventId, candidateCount);

        int rankNo = 1;
        for (Recommendation recommendation : ranked) {
            entity.addItem(recommendation.getCandidateId(), rankNo++,
                    recommendation.getScore(), recommendation.getLabel());
        }

        logRepository.save(entity);  // 아이템은 cascade 로 함께 저장된다
    }

    /**
     * 역제안(팀→유저) 추천 결과 기록.
     *
     * <p>이쪽은 분석용만이 아니다 — 팀장이 제안을 보낼 때 TeamOfferService 가 여기 남은
     * 점수/근거 문구를 찾아 team_offers 에 스냅샷으로 복사한다. 기록이 실패하면 그 제안의
     * ai_score/ai_label 이 비게 될 뿐 제안 자체는 정상 동작한다.
     *
     * @param ranked 점수 내림차순으로 이미 정렬된 결과.
     */
    public void saveTeamToUser(Long teamId, Long requestedByUserId, int candidateCount,
                               List<Recommendation> ranked) {
        TeamToUserRecommendationLog entity =
                new TeamToUserRecommendationLog(teamId, requestedByUserId, candidateCount);

        int rankNo = 1;
        for (Recommendation recommendation : ranked) {
            entity.addItem(recommendation.getCandidateId(), rankNo++,
                    recommendation.getScore(), recommendation.getLabel());
        }

        teamToUserLogRepository.save(entity);
    }

    // ── 상세 이유 캐시 ────────────────────────────────────────────────────────
    //
    // 추천 당시엔 채우지 않는다 — 목록에 뜬 모든 후보의 이유를 미리 만들면 LLM 호출이 후보 수만큼
    // 나간다. 사용자가 카드를 선택한 시점에 한 건씩 채운다.

    /** 유저→팀 상세 이유를 아이템에 캐시한다. */
    public void saveUserToTeamReason(Long itemId, String reason) {
        warnIfMissing(logRepository.updateReason(itemId, reason), itemId);
    }

    /** 팀→유저(역제안) 상세 이유를 아이템에 캐시한다. */
    public void saveTeamToUserReason(Long itemId, String reason) {
        warnIfMissing(teamToUserLogRepository.updateReason(itemId, reason), itemId);
    }

    /**
     * 방금 읽은 아이템이 사라진 경우. 로그 헤더가 지워지면 아이템도 cascade 로 함께 사라지므로
     * 불가능하진 않다. 이유 자체는 이미 생성돼 응답으로 나가므로 예외로 만들지 않는다 —
     * 다음 조회 때 캐시가 없어 AI 를 한 번 더 부를 뿐이다.
     */
    private void warnIfMissing(int updated, Long itemId) {
        if (updated == 0) {
            log.warn("상세 이유를 캐시할 추천 아이템이 없습니다 (그 사이 삭제됨). itemId={}", itemId);
        }
    }
}

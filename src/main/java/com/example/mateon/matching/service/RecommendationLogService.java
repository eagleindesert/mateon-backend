package com.example.mateon.matching.service;

import com.example.mateon.matching.client.UserToTeamRecommendationResponse.Recommendation;
import com.example.mateon.matching.domain.UserToTeamRecommendationLog;
import com.example.mateon.matching.repository.UserToTeamRecommendationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 추천 결과를 기록한다. AI 호출이 끝난 뒤 별도 트랜잭션에서 돈다
 * (RecommendationService 가 TX 밖에서 AI 를 호출하므로 저장은 여기로 분리).
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
}

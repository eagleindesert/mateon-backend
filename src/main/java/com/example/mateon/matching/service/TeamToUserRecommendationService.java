package com.example.mateon.matching.service;

import com.example.mateon.matching.client.RecommendationClient;
import com.example.mateon.matching.client.RecommendationResponse;
import com.example.mateon.matching.client.RecommendationResponse.Recommendation;
import com.example.mateon.matching.client.TeamMetadata;
import com.example.mateon.matching.client.TeamToUserRecommendationRequest;
import com.example.mateon.matching.client.UserMetadata;
import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.matching.dto.response.UserRecommendationResponseDTO;
import com.example.mateon.matching.dto.snapshot.UserDisplayInfo;
import com.example.mateon.matching.dto.snapshot.UserRecommendationSnapshot;
import com.example.mateon.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 역제안(팀→유저) 추천 흐름의 오케스트레이터. {@link RecommendationService} 의 거울상이다.
 *
 * <p>클래스 레벨 @Transactional 이 없는 게 여기서도 핵심이다 — FastAPI read-timeout 이 60 초라
 * TX 안에서 호출하면 커넥션 풀이 마른다. 조회는 RecommendationQueryService(readOnly),
 * 저장은 RecommendationLogService(@Transactional) 가 맡고 그 사이에서 AI 를 호출한다.
 * 빈이 나뉜 것도 필수다 — 같은 빈 안에서 호출하면 프록시를 타지 않아 @Transactional 이 무시된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamToUserRecommendationService {

    private final RecommendationQueryService queryService;
    private final RecommendationLogService logService;
    private final RecommendationClient client;

    /**
     * 이 팀에 맞는 유저를 적합도 순으로 추천한다. 팀장만 호출할 수 있다.
     *
     * @param limit 프론트에 내려줄 상위 건수.
     */
    public List<UserRecommendationResponseDTO> recommendUsers(Long teamId, Long leaderUserId,
        int limit) {
        // ① [TX1] 팀 벡터/메타데이터 + 후보 유저 벡터/슬롯을 스냅샷으로 → 커밋
        //    (팀장 검증과 팀 임베딩 준비 여부 확인도 여기서 한다)
        UserRecommendationSnapshot snapshot = queryService.gatherForTeam(teamId, leaderUserId);

        // 후보가 없으면 AI 를 부를 이유가 없다. "추천할 유저가 없음"은 정상 상태라 빈 배열이다.
        if (snapshot.getCandidates().isEmpty()) {
            return List.of();
        }

        // ② [TX 밖] FastAPI 호출. 수십 초가 걸려도 DB 커넥션을 잡고 있지 않다.
        RecommendationResponse ai = client.teamToUser(buildRequest(snapshot));

        // ③ 응답 정리: 우리가 보낸 후보만, 점수 내림차순
        Map<Long, UserRecommendationSnapshot.Candidate> candidatesById = snapshot.getCandidates()
            .stream()
            .collect(Collectors.toMap(candidate -> candidate.getUser().getId(),
                Function.identity()));

        List<Recommendation> ranked = ai.getRecommendations().stream()
            // 외부 서버는 신뢰할 수 없는 입력원이다 — 보낸 적 없는 candidate_id 나 점수 누락은
            // 조용히 버린다 (여기서 예외를 던지면 나머지 멀쩡한 추천까지 같이 죽는다).
            .filter(r -> r.getCandidateId() != null && r.getScore() != null)
            .filter(r -> candidatesById.containsKey(r.getCandidateId()))
            .sorted(Comparator.comparingDouble(Recommendation::getScore).reversed())
            .toList();

        int dropped = ai.getRecommendations().size() - ranked.size();
        if (dropped > 0) {
            log.warn("AI 역제안 추천 응답에서 {}건을 버렸습니다 (알 수 없는 candidate_id 또는 점수 누락). teamId={}",
                dropped, teamId);
        }

        // limit 은 AI 가 점수화해 준 건수를 넘을 수 없다 — 유저→팀 방향과 같은 제약이다
        // (AI 서버의 top_k 상한. 백엔드 limit 으로는 늘릴 수 없다).
        int candidateCount = snapshot.getCandidates().size();
        if (ranked.size() < candidateCount && ranked.size() < limit) {
            log.warn("요청한 limit={} 를 채우지 못했습니다 - AI 가 후보 {}건 중 {}건만 점수화했습니다. "
                + "AI 서버의 top_k 상한을 확인하세요 (백엔드 limit 으로는 늘릴 수 없습니다). teamId={}",
                limit, candidateCount, ranked.size(), teamId);
        }

        // ④ [TX2] 기록. 실패해도 추천 자체는 이미 성공했으므로 응답을 막지 않는다.
        //    이 기록은 제안 발송 시 ai_score/ai_label 스냅샷의 출처이기도 하다.
        try {
            logService.saveTeamToUser(teamId, leaderUserId, candidateCount, ranked);
        } catch (Exception e) {
            log.warn("역제안 추천 결과 기록 실패 (추천 응답에는 영향 없음). teamId={}", teamId, e);
        }

        // ⑤ 내려보낼 상위 N 건을 먼저 자르고, 그것들의 표시 정보(협업 온도)만 배치로 조회한다.
        List<Recommendation> top = ranked.stream().limit(Math.max(limit, 1)).toList();

        List<Long> topUserIds = top.stream().map(Recommendation::getCandidateId).toList();
        Map<Long, User> usersById = topUserIds.stream()
            .collect(Collectors.toMap(Function.identity(),
                userId -> candidatesById.get(userId).getUser()));
        Map<Long, UserDisplayInfo> displayInfo
            = queryService.loadUserDisplayInfo(topUserIds, usersById);

        return top.stream().map(r -> {
            UserRecommendationSnapshot.Candidate candidate = candidatesById.get(r.getCandidateId());
            UserDisplayInfo info = displayInfo.get(r.getCandidateId());
            return new UserRecommendationResponseDTO(candidate.getUser(), candidate.getSlot(),
                info.getCollaborationTemperature(), r.getScore(), r.getLabel());
        }).toList();
    }

    private TeamToUserRecommendationRequest buildRequest(UserRecommendationSnapshot snapshot) {
        List<TeamToUserRecommendationRequest.Candidate> candidates = snapshot.getCandidates()
            .stream()
            .map(candidate -> {
                MatchingIntentSlot slot = candidate.getSlot();
                return new TeamToUserRecommendationRequest.Candidate(
                    candidate.getUser().getId(),
                    candidate.getEmbedding(),
                    new UserMetadata(slot.getDesiredRoles(), slot.getSkills(),
                        slot.getExperienceLevel(), slot.getActivityStyle()));
            })
            .toList();

        // 질의 메타데이터는 팀의 team_embeddings 정규화 값 — 2번(embedding:refresh) 응답 그대로다.
        return new TeamToUserRecommendationRequest(snapshot.getQueryEmbedding(),
            new TeamMetadata(snapshot.getRecruitingRoles(), snapshot.getRequiredSkills(),
                snapshot.getActivityStyle(), snapshot.getBeginnerFriendly()),
            candidates);
    }
}

package com.example.mateon.matching.service;

import com.example.mateon.matching.client.RecommendationClient;
import com.example.mateon.matching.client.RecommendationResponse;
import com.example.mateon.matching.client.RecommendationResponse.Recommendation;
import com.example.mateon.matching.client.TeamMetadata;
import com.example.mateon.matching.client.UserMetadata;
import com.example.mateon.matching.client.UserToTeamRecommendationRequest;
import com.example.mateon.matching.dto.response.TeamRecommendationResponseDTO;
import com.example.mateon.matching.dto.snapshot.RecommendationSnapshot;
import com.example.mateon.matching.dto.snapshot.TeamDisplayInfo;
import com.example.mateon.teams.domain.Team;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 유저→팀 추천 흐름의 오케스트레이터.
 *
 * <p>
 * 클래스 레벨 @Transactional 이 없는 게 핵심이다 — MatchingIntentService 와 같은 이유다 (FastAPI read-timeout 이 60 초라
 * TX 안에서 호출하면 커넥션 풀이 마른다). 조회는 RecommendationQueryService(@Transactional(readOnly=true)), 저장은
 * RecommendationLogService(@Transactional) 가 맡고 그 사이에서 AI 를 호출한다. 빈이 나뉜 것도 필수다 — 같은 빈 안에서 호출하면 프록시를
 * 타지 않아 @Transactional 이 무시된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final RecommendationQueryService queryService;
    private final RecommendationLogService logService;
    private final RecommendationClient client;

    /**
     * @param eventId 지정 시 해당 활동의 팀만 후보. null 이면 전역.
     * @param limit 프론트에 내려줄 상위 건수.
     */
    public List<TeamRecommendationResponseDTO> recommendTeams(Long userId, Long eventId,
        int limit) {
        // ① [TX1] 사용자 벡터/슬롯 + 후보 팀 벡터/메타데이터를 스냅샷으로 → 커밋
        RecommendationSnapshot snapshot = queryService.gather(userId, eventId);

        // 후보가 없으면 AI 를 부를 이유가 없다. "추천할 팀이 없음"은 정상 상태라 빈 배열이다.
        if (snapshot.getCandidates().isEmpty()) {
            return List.of();
        }

        // ② [TX 밖] FastAPI 호출. 수십 초가 걸려도 DB 커넥션을 잡고 있지 않다.
        RecommendationResponse ai = client.userToTeam(buildRequest(snapshot));

        // ③ 응답 정리: 우리가 보낸 후보만, 점수 내림차순
        Map<Long, Team> teamsById = snapshot.getCandidates().stream()
            .map(RecommendationSnapshot.Candidate::getTeam)
            .collect(Collectors.toMap(Team::getId, Function.identity()));

        List<Recommendation> ranked = ai.getRecommendations().stream()
            // 외부 서버는 신뢰할 수 없는 입력원이다 — 보낸 적 없는 candidate_id 나 점수 누락은
            // 조용히 버린다 (여기서 예외를 던지면 나머지 멀쩡한 추천까지 같이 죽는다).
            .filter(r -> r.getCandidateId() != null && r.getScore() != null)
            .filter(r -> teamsById.containsKey(r.getCandidateId()))
            .sorted(Comparator.comparingDouble(Recommendation::getScore)
                .reversed())
            .toList();

        int dropped = ai.getRecommendations().size() - ranked.size();
        if (dropped > 0) {
            log.warn("AI 추천 응답에서 {}건을 버렸습니다 (알 수 없는 candidate_id 또는 점수 누락). userId={}",
                dropped, userId);
        }

        // limit 은 AI 가 점수화해 준 건수를 넘을 수 없다 — 우리는 AI 응답에 담긴 것만 내려보내고
        // limit 은 그걸 더 자를 뿐 늘리지 못한다. AI 서버에 top_k 상한(실측 10건)이 걸려 있어
        // 후보를 아무리 많이 보내도 그 이상은 오지 않는다.
        // 상한 값을 코드에 박지 않고 "후보보다 적게 왔는데 그게 요청 건수에도 못 미친다"는 사실로
        // 판정한다 — AI 쪽 상한이 바뀌어도 이 진단은 그대로 맞는다.
        int candidateCount = snapshot.getCandidates().size();
        if (ranked.size() < candidateCount && ranked.size() < limit) {
            log.warn("요청한 limit={} 를 채우지 못했습니다 - AI 가 후보 {}건 중 {}건만 점수화했습니다. "
                + "AI 서버의 top_k 상한을 확인하세요 (백엔드 limit 으로는 늘릴 수 없습니다). userId={}",
                limit, candidateCount, ranked.size(), userId);
        }

        // ④ [TX2] 기록. 실패해도 추천 자체는 이미 성공했으므로 응답을 막지 않는다.
        try {
            logService.save(userId, eventId, snapshot.getCandidates().size(), ranked);
        } catch (Exception e) {
            log.warn("추천 결과 기록 실패 (추천 응답에는 영향 없음). userId={}", userId, e);
        }

        // ⑤ 내려보낼 상위 N 건을 먼저 자르고, 그것들의 표시 정보(활동/인원)만 배치로 조회한다.
        // 후보 200개를 전부 조회해 10개만 쓰는 낭비를 피하려고 자르기를 조회 앞에 둔다.
        List<Recommendation> top = ranked.stream().limit(Math.max(limit, 1)).toList();

        List<Long> topTeamIds = top.stream().map(Recommendation::getCandidateId).toList();
        Map<Long, TeamDisplayInfo> displayInfo
            = queryService.loadDisplayInfo(topTeamIds, teamsById);

        return top.stream().map(r -> {
            TeamDisplayInfo info = displayInfo.get(r.getCandidateId());
            return new TeamRecommendationResponseDTO(teamsById.get(r.getCandidateId()),
                info.getEvent(), info.getCurrentMemberCount(), r.getScore(),
                r.getLabel());
        }).toList();
    }

    private UserToTeamRecommendationRequest buildRequest(RecommendationSnapshot snapshot) {
        List<UserToTeamRecommendationRequest.Candidate> candidates = snapshot
            .getCandidates().stream()
            .map(candidate -> new UserToTeamRecommendationRequest.Candidate(
            candidate.getTeam().getId(),
            candidate.getEmbedding().getEmbedding(),
            // 메타데이터는 teams 원본이 아니라 team_embeddings 의 AI 정규화 값을
            // 쓴다 (이유는 TeamMetadata 주석 참고).
            new TeamMetadata(
                candidate.getEmbedding().getRecruitingRoles(),
                candidate.getEmbedding().getRequiredSkills(),
                candidate.getEmbedding().getActivityStyle(),
                candidate.getEmbedding().getBeginnerFriendly())))
            .toList();

        return new UserToTeamRecommendationRequest(snapshot.getQueryEmbedding(),
            new UserMetadata(snapshot.getDesiredRoles(), snapshot.getSkills(),
                snapshot.getExperienceLevel(), snapshot.getActivityStyle()),
            candidates);
    }
}

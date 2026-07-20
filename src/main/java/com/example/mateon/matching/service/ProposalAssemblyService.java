package com.example.mateon.matching.service;

import com.example.mateon.matching.client.ProposalAssemblyRequest;
import com.example.mateon.matching.client.ProposalClient;
import com.example.mateon.matching.client.ProposalResponse;
import com.example.mateon.matching.dto.response.ProposalDraftResponseDTO;
import com.example.mateon.matching.dto.snapshot.ProposalSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Function;

/**
 * 최종 제안 조립 흐름의 오케스트레이터.
 *
 * <p>클래스 레벨 @Transactional 이 없는 건 {@link RecommendationService} /
 * {@link RecommendationReasonService} 와 같은 이유다 — FastAPI read-timeout 이 60 초라 TX 안에서
 * 호출하면 커넥션 풀이 마른다. 조회는 RecommendationQueryService(readOnly) 가 맡고, 그 TX 가
 * 커밋된 뒤에 AI 를 부른다. 빈이 나뉜 것도 필수다(같은 빈 안에서 호출하면 프록시를 타지 않는다).
 *
 * <p>다른 오케스트레이터와 달리 <b>저장 단계가 없다</b>. 여기서 만든 건 초안일 뿐이고, 사용자가
 * 확인·수정한 뒤 기존 발송 API(POST /api/teams/{teamId}/apply · /offers)로 보낸다. AI 가 쓴 글이
 * 사용자 검토 없이 남의 이름으로 나가지 않게 하려는 것이고, 덕분에 이 경로는 부수효과가 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProposalAssemblyService {

    private static final String USER_TO_TEAM = "USER_TO_TEAM";
    private static final String TEAM_TO_USER = "TEAM_TO_USER";

    private final RecommendationQueryService queryService;
    private final ProposalClient client;

    /**
     * 유저가 추천받은 팀에 보낼 지원 문구 초안.
     *
     * @param userId 요청자. 자기 추천 이력에서만 찾으므로 이 값이 곧 권한이다.
     */
    public ProposalDraftResponseDTO draftForTeam(Long userId, Long teamId) {
        return assemble(queryService.gatherProposalForUserToTeam(userId, teamId),
                USER_TO_TEAM, client::userToTeam);
    }

    /**
     * 팀장이 추천받은 유저에게 보낼 제안 문구 초안 (역제안).
     *
     * @param leaderUserId 요청자. 팀장이 아니면 조회 단계에서 403 이 난다.
     */
    public ProposalDraftResponseDTO draftForUser(Long teamId, Long targetUserId, Long leaderUserId) {
        return assemble(queryService.gatherProposalForTeamToUser(teamId, targetUserId, leaderUserId),
                TEAM_TO_USER, client::teamToUser);
    }

    /**
     * 두 방향이 공유하는 본체. 방향에 따라 다른 건 sender/receiver 자리와 어느 AI 경로를
     * 부르느냐뿐이다 (요약의 후보/대상 자리는 이미 조회 단계에서 맞춰져 온다).
     */
    private ProposalDraftResponseDTO assemble(
            ProposalSnapshot snapshot, String direction,
            Function<ProposalAssemblyRequest, ProposalResponse> aiCall) {

        boolean userToTeam = USER_TO_TEAM.equals(direction);

        // [TX 밖] FastAPI 호출. 수십 초가 걸려도 DB 커넥션을 잡고 있지 않다.
        ProposalResponse assembled = aiCall.apply(new ProposalAssemblyRequest(
                snapshot.getUserId(),
                snapshot.getTeamId(),
                snapshot.getContestId(),
                userToTeam ? snapshot.getUserId() : snapshot.getTeamId(),   // sender
                userToTeam ? snapshot.getTeamId() : snapshot.getUserId(),   // receiver
                snapshot.getIntentId(),
                snapshot.getSynergyScore(),
                snapshot.getCandidateSummary(),
                snapshot.getTargetSummary()));

        // synergyScore 는 AI 가 되돌려 준 값이 아니라 우리가 읽은 추천 이력의 값을 쓴다 —
        // 조립 과정에서 바뀔 수 없는 값이라 출처를 하나로 고정해 둔다.
        return new ProposalDraftResponseDTO(direction,
                snapshot.getTeamId(), snapshot.getUserId(), snapshot.getSynergyScore(),
                assembled.getSummary(), assembled.getMessage());
    }
}

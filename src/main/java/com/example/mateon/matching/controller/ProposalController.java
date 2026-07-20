package com.example.mateon.matching.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.matching.dto.request.ProposalAssemblyRequestDTO;
import com.example.mateon.matching.dto.request.UserProposalRequestDTO;
import com.example.mateon.matching.dto.response.ProposalDraftResponseDTO;
import com.example.mateon.matching.service.ProposalAssemblyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 최종 제안 조립 API — 추천에서 고른 상대에게 보낼 지원/제안 문구를 AI 가 써 준다.
 *
 * <p><b>여기서는 아무것도 저장하지 않는다.</b> 응답은 초안이고, 사용자가 화면에서 고친 뒤
 * 기존 발송 API 로 보낸다. 그때 생기는 id 가 AI 명세의 proposal_id 다:
 *
 * <pre>
 * GET  /api/matching/recommendations/user-to-team   (추천 목록)
 * POST /api/matching/recommendations/reason/...     (왜 이 팀인가)
 * POST /api/matching/proposals/user-to-team         (여기 — 문구 초안)
 *      ↓ 사용자가 확인·수정
 * POST /api/teams/{teamId}/apply                    (발송 → applicationId)
 * </pre>
 *
 * <p>선행 조건은 추천 이력이다. 추천에 뜬 적 없는 상대면 404 RECOMMENDATION_NOT_FOUND —
 * 적합도(synergy_score)의 출처가 추천 이력뿐이라 그것 없이는 조립할 수 없다. 추천을 거치지 않고
 * 그냥 지원하는 건 기존 발송 API 로 하면 된다.
 *
 * <p>조회처럼 보이는데 POST 인 이유: LLM 호출을 유발한다 (상세 이유와 같은 판단).
 * 다만 저장하지 않으므로 같은 요청을 반복하면 매번 새 문구가 나온다 — 마음에 안 들 때 다시
 * 뽑는 게 정상 동작이다 (상세 이유는 반대로 한 번 정해지면 고정된다).
 */
@RestController
@RequestMapping("/api/matching/proposals")
@RequiredArgsConstructor
public class ProposalController {

    private final ProposalAssemblyService proposalAssemblyService;

    /**
     * 추천받은 팀에 보낼 지원 문구 초안.
     *
     * <p>선행 호출은 GET /api/matching/recommendations/user-to-team 이다.
     */
    @PostMapping("/user-to-team")
    public ResponseEntity<ApiResponse<ProposalDraftResponseDTO>> draftForTeam(
            @Valid @RequestBody ProposalAssemblyRequestDTO request,
            Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(
                proposalAssemblyService.draftForTeam(userId, request.getTeamId())));
    }

    /**
     * 추천받은 유저에게 보낼 제안 문구 초안. 팀장만 호출할 수 있다.
     *
     * <p>선행 호출은 GET /api/matching/recommendations/team-to-user 다.
     *
     * @param request teamId 의 팀장이 아니면 403.
     */
    @PostMapping("/team-to-user")
    public ResponseEntity<ApiResponse<ProposalDraftResponseDTO>> draftForUser(
            @Valid @RequestBody UserProposalRequestDTO request,
            Authentication authentication
    ) {
        Long leaderUserId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(proposalAssemblyService.draftForUser(
                request.getTeamId(), request.getUserId(), leaderUserId)));
    }
}

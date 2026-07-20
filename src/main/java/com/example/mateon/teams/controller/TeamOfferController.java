package com.example.mateon.teams.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.teams.dto.request.TeamOfferCreateRequestDTO;
import com.example.mateon.teams.dto.request.TeamOfferRespondRequestDTO;
import com.example.mateon.teams.dto.response.TeamOfferResponseDTO;
import com.example.mateon.teams.service.TeamOfferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 역제안 API — 팀장이 유저에게 제안하고, 유저가 수락 여부를 결정한다.
 *
 * <p>지원서 API(TeamController 의 /applications/*)와 방향이 반대인 경로다. URL 도 그 모양을
 * 그대로 뒤집어 두었다: 지원서는 유저가 {teamId}/apply 로 시작하고 팀장이 처리하지만,
 * 제안은 팀장이 {teamId}/offers 로 시작하고 유저가 처리한다.
 *
 * <p>제안 대상은 GET /api/matching/recommendations/team-to-user 로 찾는다.
 */
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamOfferController {

    private final TeamOfferService teamOfferService;

    /** (팀장용) 유저에게 제안 발송. */
    @PostMapping("/{teamId}/offers")
    public ResponseEntity<ApiResponse<TeamOfferResponseDTO>> createOffer(
            @PathVariable Long teamId,
            @Valid @RequestBody TeamOfferCreateRequestDTO request,
            Authentication authentication
    ) {
        TeamOfferResponseDTO response = teamOfferService.createOffer(
                teamId, request.getUserId(), request.getMessage(),
                Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /** (팀장용) 이 팀이 보낸 제안 목록. */
    @GetMapping("/{teamId}/offers")
    public ResponseEntity<ApiResponse<List<TeamOfferResponseDTO>>> getTeamOffers(
            @PathVariable Long teamId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                teamOfferService.getTeamOffers(teamId, Long.valueOf(authentication.getName()))));
    }

    /** 내가 받은 제안 목록. */
    @GetMapping("/offers/me")
    public ResponseEntity<ApiResponse<List<TeamOfferResponseDTO>>> getMyOffers(
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                teamOfferService.getMyOffers(Long.valueOf(authentication.getName()))));
    }

    /**
     * 받은 제안에 응답한다. 수락하면 그 자리에서 팀원이 된다 (팀장의 재승인 없음).
     *
     * <p>이미 응답했거나 팀장이 취소한 제안이면 400 OFFER_ALREADY_RESPONDED,
     * 그 사이 정원이 찼거나 활동이 끝났으면 400 TEAM_RECRUITMENT_CLOSED 다.
     */
    @PatchMapping("/offers/{offerId}")
    public ResponseEntity<ApiResponse<TeamOfferResponseDTO>> respondToOffer(
            @PathVariable Long offerId,
            @Valid @RequestBody TeamOfferRespondRequestDTO request,
            Authentication authentication
    ) {
        TeamOfferResponseDTO response = teamOfferService.respond(
                offerId, request.getAccepted(), Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /** (팀장용) 아직 응답받지 않은 제안 회수. */
    @DeleteMapping("/offers/{offerId}")
    public ResponseEntity<ApiResponse<String>> cancelOffer(
            @PathVariable Long offerId,
            Authentication authentication
    ) {
        teamOfferService.cancelOffer(offerId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success("제안이 취소되었습니다."));
    }
}

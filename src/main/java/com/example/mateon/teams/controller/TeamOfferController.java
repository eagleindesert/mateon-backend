package com.example.mateon.teams.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.teams.dto.request.TeamOfferCreateRequestDTO;
import com.example.mateon.teams.dto.request.TeamOfferRespondRequestDTO;
import com.example.mateon.teams.dto.response.TeamOfferResponseDTO;
import com.example.mateon.teams.service.TeamOfferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "역제안 (팀→유저)", description = "팀장이 유저에게 먼저 제안하고, 유저가 수락하면 즉시 팀원이 되는 경로")
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamOfferController {

    private final TeamOfferService teamOfferService;

    /** (팀장용) 유저에게 제안 발송. */
    @Operation(summary = "(팀장용) 유저에게 제안 발송",
            description = """
                    이미 팀원이거나, 지원서를 냈거나, 이미 제안을 받은 유저에게는 보낼 수 없다
                    (400 DUPLICATE_RESOURCE). AI 점수/근거는 요청 값이 아니라 서버가 추천 이력에서
                    찾아 넣으므로 본문에 담아 보낼 필요가 없다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 이 팀의 팀장만 제안할 수 있습니다.
            DUPLICATE_RESOURCE — 이미 팀원·지원자이거나 제안을 받은 유저입니다.
            INVALID_INPUT — 자기 자신에게는 제안할 수 없습니다.
            TEAM_RECRUITMENT_CLOSED — 모집이 마감되었거나 종료된 팀입니다.
            RESOURCE_NOT_FOUND — 팀을 찾을 수 없습니다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "USER_NOT_FOUND — 제안 대상 사용자를 찾을 수 없습니다.")
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
    @Operation(summary = "(팀장용) 이 팀이 보낸 제안 목록", description = "최신순. 팀장만 조회할 수 있다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 이 팀의 팀장만 조회할 수 있습니다.
            RESOURCE_NOT_FOUND — 팀을 찾을 수 없습니다.""")
    @GetMapping("/{teamId}/offers")
    public ResponseEntity<ApiResponse<List<TeamOfferResponseDTO>>> getTeamOffers(
            @PathVariable Long teamId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                teamOfferService.getTeamOffers(teamId, Long.valueOf(authentication.getName()))));
    }

    /** 내가 받은 제안 목록. */
    @Operation(summary = "내가 받은 제안 목록", description = "최신순.")
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
    @Operation(summary = "받은 제안에 응답 (수락/거절)",
            description = """
                    수락하면 팀장의 재승인 없이 그 자리에서 팀원이 되고, 정원이 차면 모집이 마감된다.
                    수락에는 학교 인증이 필요하다.

                    이미 응답했거나 팀장이 회수한 제안이면 400 OFFER_ALREADY_RESPONDED,
                    그 사이 정원이 찼거나 활동이 끝났으면 400 TEAM_RECRUITMENT_CLOSED.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 나에게 온 제안이 아닙니다.
            OFFER_ALREADY_RESPONDED — 이미 처리된 제안입니다.
            TEAM_RECRUITMENT_CLOSED — 모집이 마감되었거나 종료된 팀입니다.
            SCHOOL_NOT_VERIFIED — 수락에는 학교 인증이 필요합니다.
            RESOURCE_NOT_FOUND — 제안을 찾을 수 없습니다.""")
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
    @Operation(summary = "(팀장용) 제안 회수", description = "아직 PENDING 인 제안만 회수할 수 있다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 이 팀의 팀장만 회수할 수 있습니다.
            OFFER_ALREADY_RESPONDED — 이미 처리된 제안입니다.
            RESOURCE_NOT_FOUND — 제안을 찾을 수 없습니다.""")
    @DeleteMapping("/offers/{offerId}")
    public ResponseEntity<ApiResponse<String>> cancelOffer(
            @PathVariable Long offerId,
            Authentication authentication
    ) {
        teamOfferService.cancelOffer(offerId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success("제안이 취소되었습니다."));
    }
}

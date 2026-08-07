package com.example.mateon.teams.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.teams.dto.request.TeamApplicationRequestDTO;
import com.example.mateon.teams.dto.request.TeamRequestDTO;
import com.example.mateon.teams.dto.response.TeamApplicationResponseDTO;
import com.example.mateon.teams.dto.response.TeamDetailResponseDTO;
import com.example.mateon.teams.dto.response.TeamResponseDTO;
import com.example.mateon.teams.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "팀 모집/지원", description = "팀 모집글 CRUD 와 지원서 흐름(지원 → 팀장 승인/거절)")
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    // 1. 팀 모집글 조회 (통합)
    @Operation(summary = "팀 모집글 목록 조회",
            description = """
                    필터는 배타적으로 적용된다 — myPosts > eventId > category 순으로 하나만 걸린다.
                    category 는 "전체"(전부) 와 "자율"(연결된 활동이 없는 팀) 이 특수값이다.
                    로그인 없이도 조회할 수 있다.""")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @GetMapping
    public ResponseEntity<ApiResponse<List<TeamResponseDTO>>> getTeams(
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "false") boolean myPosts,
            Authentication authentication
    ) {
        Long userId = (authentication != null) ? Long.valueOf(authentication.getName()) : null;
        List<TeamResponseDTO> responses = teamService.getTeams(eventId, category, myPosts, userId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
    // 1-2. 팀 모집글 상세 조회 (단건)
    // 리더 여부(isLeader)와 지원 여부(hasApplied)가 포함된 DTO 반환
    @Operation(summary = "팀 모집글 상세 조회",
            description = """
                    목록 응답에 조회자 기준 정보(isLeader, hasApplied, myApplicationStatus)와
                    확정 팀원 명단(members)이 더해진다. members 는 팀장을 LEADER 로 포함하며
                    currentMemberCount 와 항상 같은 크기다.

                    토큰 없이도 호출할 수 있고, 그때 조회자 기준 필드는 false/null 로 내려간다.""")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @GetMapping("/{teamId}")
    public ResponseEntity<ApiResponse<TeamDetailResponseDTO>> getTeam(
            @PathVariable Long teamId,
            Authentication authentication
    ) {
        Long userId = (authentication != null) ? Long.valueOf(authentication.getName()) : null;
        TeamDetailResponseDTO response = teamService.getTeamDetail(teamId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }



    // 2. 팀 모집글 작성
    @Operation(summary = "팀 모집글 작성",
            description = """
                    학교 인증(재학생)이 완료된 유저만 쓸 수 있다 — 미인증이면 400 SCHOOL_NOT_VERIFIED.
                    작성자는 즉시 LEADER 로 팀원에 포함되므로 생성 직후 인원은 1명이다.
                    커밋 후 비동기로 AI 임베딩이 계산된다(추천에 쓰임).""")
    @PostMapping
    public ResponseEntity<ApiResponse<TeamResponseDTO>> createTeam(
            @Valid @RequestBody TeamRequestDTO request,
            Authentication authentication
    ) {
        TeamResponseDTO response = teamService.createTeam(request, Long.valueOf(authentication.getName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // 3. 팀 모집글 수정
    @Operation(summary = "팀 모집글 수정", description = "팀장만 가능(403 FORBIDDEN_ACCESS). AI 임베딩이 재계산된다.")
    @PutMapping("/{teamId}")
    public ResponseEntity<ApiResponse<TeamResponseDTO>> updateTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody TeamRequestDTO request,
            Authentication authentication
    ) {
        TeamResponseDTO response = teamService.updateTeam(teamId, request, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 4. 팀 모집글 삭제
    @Operation(summary = "팀 모집글 삭제", description = "팀장만 가능. 지원서·제안·팀원 소속이 함께 정리된다.")
    @DeleteMapping("/{teamId}")
    public ResponseEntity<ApiResponse<String>> deleteTeam(
            @PathVariable Long teamId,
            Authentication authentication
    ) {
        teamService.deleteTeam(teamId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success("삭제되었습니다."));
    }

    // 5. 특정 팀에 지원하기
    @Operation(summary = "팀 지원",
            description = """
                    학교 인증 필요. 본인이 만든 팀에는 지원할 수 없고, 같은 팀에 두 번 지원할 수 없다.
                    지원서가 생길 뿐 팀원이 되지는 않는다 — 팀장 승인이 있어야 소속이 생긴다.""")
    @PostMapping("/{teamId}/apply")
    public ResponseEntity<ApiResponse<String>> applyToTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody TeamApplicationRequestDTO request,
            Authentication authentication
    ) {
        teamService.applyToTeam(teamId, request, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success("지원이 완료되었습니다."));
    }

    // 6. 내가 쓴 지원서 목록 보기
    @Operation(summary = "내가 쓴 지원서 목록", description = "상태(PENDING/APPROVED/REJECTED)가 함께 내려간다.")
    @GetMapping("/applications/me")
    public ResponseEntity<ApiResponse<List<TeamApplicationResponseDTO>>> getMyApplications(
            Authentication authentication
    ) {
        List<TeamApplicationResponseDTO> responses = teamService.getMyApplications(Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    // 7. (팀장용) 내 팀에 온 지원서 목록 보기
    @Operation(summary = "(팀장용) 내 팀에 온 지원서 목록",
            description = """
                    지원 이력이지 팀원 명단이 아니다 — 역제안으로 합류한 사람은 지원서가 없어 여기
                    나타나지 않는다. 확정 팀원 명단이 필요하면 `GET /api/teams/{teamId}` 의
                    members 를 쓴다.""")
    @GetMapping("/{teamId}/applications")
    public ResponseEntity<ApiResponse<List<TeamApplicationResponseDTO>>> getApplicationsForTeam(
            @PathVariable Long teamId,
            Authentication authentication
    ) {
        List<TeamApplicationResponseDTO> responses = teamService.getApplicationsForMyTeam(teamId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    // 8. (팀장용) 지원서 승인/거절 처리
    @Operation(summary = "(팀장용) 지원서 승인/거절",
            description = """
                    승인하면 그 자리에서 팀원이 되고, 정원이 차면 모집이 자동 마감된다.

                    PENDING 인 지원서에만 쓸 수 있다 — 이미 처리한 지원서를 다시 부르면
                    400 APPLICATION_ALREADY_PROCESSED. 승인을 되돌리는 용도가 아니다.""")
    @PatchMapping("/applications/{applicationId}")
    public ResponseEntity<ApiResponse<String>> processApplication(
            @PathVariable Long applicationId,
            @RequestParam boolean isApproved,
            Authentication authentication
    ) {
        teamService.processApplication(applicationId, isApproved, Long.valueOf(authentication.getName()));
        String message = isApproved ? "승인되었습니다." : "거절되었습니다.";
        return ResponseEntity.ok(ApiResponse.success(message));
    }
    // 9. 지원서 수정 (지원자용)
    @Operation(summary = "지원서 수정", description = "작성자 본인만, PENDING 상태에서만 가능.")
    @PutMapping("/applications/{applicationId}")
    public ResponseEntity<ApiResponse<String>> updateApplication(
            @PathVariable Long applicationId,
            @Valid @RequestBody TeamApplicationRequestDTO request,
            Authentication authentication
    ) {
        teamService.updateApplication(applicationId, request, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success("지원서가 수정되었습니다."));
    }

    // 10. 지원 취소 (지원자용)
    @Operation(summary = "지원 취소", description = "작성자 본인만, PENDING 상태에서만 가능.")
    @DeleteMapping("/applications/{applicationId}")
    public ResponseEntity<ApiResponse<String>> cancelApplication(
            @PathVariable Long applicationId,
            Authentication authentication
    ) {
        teamService.cancelApplication(applicationId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success("지원이 취소되었습니다."));
    }
    // 11. [NEW] 지원서 상세 조회 (팀장 OR 지원자 본인만 가능)
    @Operation(summary = "지원서 상세 조회", description = "지원 당사자이거나 해당 팀의 팀장이어야 한다(403).")
    @GetMapping("/applications/{applicationId}")
    public ResponseEntity<ApiResponse<TeamApplicationResponseDTO>> getApplicationDetail(
            @PathVariable Long applicationId,
            Authentication authentication
    ) {
        TeamApplicationResponseDTO response = teamService.getApplicationDetail(applicationId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
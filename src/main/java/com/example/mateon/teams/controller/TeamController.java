package com.example.mateon.teams.controller;

import com.example.mateon.common.dto.BaseResponse;
import com.example.mateon.teams.dto.request.TeamApplicationRequestDTO;
import com.example.mateon.teams.dto.request.TeamRequestDTO;
import com.example.mateon.teams.dto.response.TeamApplicationResponseDTO;
import com.example.mateon.teams.dto.response.TeamDetailResponseDTO;
import com.example.mateon.teams.dto.response.TeamResponseDTO;
import com.example.mateon.teams.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
    public ResponseEntity<BaseResponse<List<TeamResponseDTO>>> getTeams(
      @RequestParam(required = false) Long eventId,
      @Parameter(description = "\"전체\", \"자율\" 또는 활동 분류 enum 이름(CONTEST, EXTERNAL, SCHOOL, ETC). "
        + "정확히 일치해야 하며, 한글 라벨을 포함해 그 외 값을 보내면 400 이다.")
      @RequestParam(required = false) String category,
      @RequestParam(required = false, defaultValue = "false") boolean myPosts,
      Authentication authentication
    ) {
        Long userId = (authentication != null) ? Long.valueOf(authentication.getName()) : null;
        List<TeamResponseDTO> responses = teamService.getTeams(eventId, category, myPosts, userId);
        return ResponseEntity.ok(BaseResponse.success(responses));
    }

    // 1-2. 팀 모집글 상세 조회 (단건)
    // 리더 여부(leader)와 지원 여부(hasApplied)가 포함된 DTO 반환
    @Operation(summary = "팀 모집글 상세 조회",
      description = """
                    목록 응답에 조회자 기준 정보(leader, hasApplied, myApplicationStatus)와
                    확정 팀원 명단(members)이 더해진다. members 는 팀장을 LEADER 로 포함하며
                    currentMemberCount 와 항상 같은 크기다.

                    주의: 조회자가 팀장인지는 최상위 `leader` 이고, 명단 한 줄이 팀장인지는
                    `members[].isLeader` 다. 한 응답 안에서 이름이 다르니 헷갈리지 말 것 —
                    자바 필드가 is- 로 시작해 Jackson 이 접두어를 뗀 결과이고, 프론트가 이미
                    이 이름으로 읽고 있어 그대로 둔다.

                    토큰 없이도 호출할 수 있고, 그때 조회자 기준 필드는 false/null 로 내려간다.""")
    @ApiResponse(responseCode = "200", description = "팀 모집글 상세.")
    @ApiResponse(responseCode = "400",
      description = "RESOURCE_NOT_FOUND — 팀을 찾을 수 없습니다.")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @GetMapping("/{teamId}")
    public ResponseEntity<BaseResponse<TeamDetailResponseDTO>> getTeam(
      @PathVariable Long teamId,
      Authentication authentication
    ) {
        Long userId = (authentication != null) ? Long.valueOf(authentication.getName()) : null;
        TeamDetailResponseDTO response = teamService.getTeamDetail(teamId, userId);
        return ResponseEntity.ok(BaseResponse.success(response));
    }

    // 2. 팀 모집글 작성
    @Operation(summary = "팀 모집글 작성",
      description = """
                    학교 인증(재학생)이 완료된 유저만 쓸 수 있다 — 미인증이면 400 SCHOOL_NOT_VERIFIED.
                    작성자는 즉시 LEADER 로 팀원에 포함되므로 생성 직후 인원은 1명이다.
                    커밋 후 비동기로 AI 임베딩이 계산된다(추천에 쓰임).""")
    @ApiResponse(responseCode = "201", description = "만들어진 팀 모집글.")
    @ApiResponse(responseCode = "400", description = """
            SCHOOL_NOT_VERIFIED — 학교 인증이 필요한 기능입니다.
            RESOURCE_NOT_FOUND — 연결하려는 활동을 찾을 수 없습니다.""")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @PostMapping
    public ResponseEntity<BaseResponse<TeamResponseDTO>> createTeam(
      @Valid @RequestBody TeamRequestDTO request,
      Authentication authentication
    ) {
        TeamResponseDTO response = teamService.createTeam(request, Long.valueOf(authentication.getName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(BaseResponse.success(response));
    }

    // 3. 팀 모집글 수정
    @Operation(summary = "팀 모집글 수정", description = "팀장만 가능(400 FORBIDDEN_ACCESS). AI 임베딩이 재계산된다.")
    @ApiResponse(responseCode = "200", description = "수정된 팀 모집글.")
    @ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 팀장만 수정할 수 있습니다.
            RESOURCE_NOT_FOUND — 팀을 찾을 수 없습니다.""")
    @PutMapping("/{teamId}")
    public ResponseEntity<BaseResponse<TeamResponseDTO>> updateTeam(
      @PathVariable Long teamId,
      @Valid @RequestBody TeamRequestDTO request,
      Authentication authentication
    ) {
        TeamResponseDTO response = teamService.updateTeam(teamId, request, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(BaseResponse.success(response));
    }

    // 4. 팀 모집글 삭제
    @Operation(summary = "팀 모집글 삭제", description = "팀장만 가능. 지원서·제안·팀원 소속이 함께 정리된다.")
    @ApiResponse(responseCode = "200", description = "삭제 완료. data 에 안내 문구가 문자열로 들어온다.")
    @ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 팀장만 삭제할 수 있습니다.
            RESOURCE_NOT_FOUND — 팀을 찾을 수 없습니다.""")
    @DeleteMapping("/{teamId}")
    public ResponseEntity<BaseResponse<String>> deleteTeam(
      @PathVariable Long teamId,
      Authentication authentication
    ) {
        teamService.deleteTeam(teamId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(BaseResponse.success("삭제되었습니다."));
    }

    // 5. 특정 팀에 지원하기
    @Operation(summary = "팀 지원",
      description = """
                    학교 인증 필요. 본인이 만든 팀에는 지원할 수 없고, 같은 팀에 두 번 지원할 수 없다.
                    지원서가 생길 뿐 팀원이 되지는 않는다 — 팀장 승인이 있어야 소속이 생긴다.""")
    @ApiResponse(responseCode = "200", description = "지원 완료. data 에 안내 문구가 문자열로 들어온다.")
    @ApiResponse(responseCode = "400", description = """
            SCHOOL_NOT_VERIFIED — 학교 인증이 필요한 기능입니다.
            RESOURCE_NOT_FOUND — 팀을 찾을 수 없습니다.
            "본인이 개설한 팀에는 지원할 수 없습니다." / "이미 지원한 팀입니다."
            — 이 둘은 ErrorCode 없이 message 로만 내려간다.""")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @PostMapping("/{teamId}/apply")
    public ResponseEntity<BaseResponse<String>> applyToTeam(
      @PathVariable Long teamId,
      @Valid @RequestBody TeamApplicationRequestDTO request,
      Authentication authentication
    ) {
        teamService.applyToTeam(teamId, request, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(BaseResponse.success("지원이 완료되었습니다."));
    }

    // 6. 내가 쓴 지원서 목록 보기
    @Operation(summary = "내가 쓴 지원서 목록", description = "상태(PENDING/APPROVED/REJECTED)가 함께 내려간다.")
    @GetMapping("/applications/me")
    public ResponseEntity<BaseResponse<List<TeamApplicationResponseDTO>>> getMyApplications(
      Authentication authentication
    ) {
        List<TeamApplicationResponseDTO> responses = teamService.getMyApplications(Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(BaseResponse.success(responses));
    }

    // 7. (팀장용) 내 팀에 온 지원서 목록 보기
    @Operation(summary = "(팀장용) 내 팀에 온 지원서 목록",
      description = """
                    지원 이력이지 팀원 명단이 아니다 — 역제안으로 합류한 사람은 지원서가 없어 여기
                    나타나지 않는다. 확정 팀원 명단이 필요하면 `GET /api/teams/{teamId}` 의
                    members 를 쓴다.""")
    @ApiResponse(responseCode = "200", description = "이 팀에 온 지원서 목록. 없으면 빈 배열이다.")
    @ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 이 팀의 팀장만 조회할 수 있습니다.
            RESOURCE_NOT_FOUND — 팀을 찾을 수 없습니다.""")
    @GetMapping("/{teamId}/applications")
    public ResponseEntity<BaseResponse<List<TeamApplicationResponseDTO>>> getApplicationsForTeam(
      @PathVariable Long teamId,
      Authentication authentication
    ) {
        List<TeamApplicationResponseDTO> responses = teamService.getApplicationsForMyTeam(teamId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(BaseResponse.success(responses));
    }

    // 8. (팀장용) 지원서 승인/거절 처리
    @Operation(summary = "(팀장용) 지원서 승인/거절",
      description = """
                    승인하면 그 자리에서 팀원이 되고, 정원이 차면 모집이 자동 마감된다.

                    PENDING 인 지원서에만 쓸 수 있다 — 이미 처리한 지원서를 다시 부르면
                    400 APPLICATION_ALREADY_PROCESSED. 승인을 되돌리는 용도가 아니다.""")
    @ApiResponse(responseCode = "200", description = "처리 완료. data 에 승인/거절 문구가 문자열로 들어온다.")
    @ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 이 팀의 팀장만 처리할 수 있습니다.
            APPLICATION_ALREADY_PROCESSED — 이미 처리된 지원서입니다.
            RESOURCE_NOT_FOUND — 지원서를 찾을 수 없습니다.""")
    @PatchMapping("/applications/{applicationId}")
    public ResponseEntity<BaseResponse<String>> processApplication(
      @PathVariable Long applicationId,
      @RequestParam boolean isApproved,
      Authentication authentication
    ) {
        teamService.processApplication(applicationId, isApproved, Long.valueOf(authentication.getName()));
        String message = isApproved ? "승인되었습니다." : "거절되었습니다.";
        return ResponseEntity.ok(BaseResponse.success(message));
    }

    // 9. 지원서 수정 (지원자용)
    @Operation(summary = "지원서 수정", description = "작성자 본인만, PENDING 상태에서만 가능.")
    @ApiResponse(responseCode = "200", description = "수정 완료. data 에 안내 문구가 문자열로 들어온다.")
    @ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 작성자 본인이 아니거나 이미 처리된 지원서입니다.
            RESOURCE_NOT_FOUND — 지원서를 찾을 수 없습니다.""")
    @PutMapping("/applications/{applicationId}")
    public ResponseEntity<BaseResponse<String>> updateApplication(
      @PathVariable Long applicationId,
      @Valid @RequestBody TeamApplicationRequestDTO request,
      Authentication authentication
    ) {
        teamService.updateApplication(applicationId, request, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(BaseResponse.success("지원서가 수정되었습니다."));
    }

    // 10. 지원 취소 (지원자용)
    @Operation(summary = "지원 취소", description = "작성자 본인만, PENDING 상태에서만 가능.")
    @ApiResponse(responseCode = "200", description = "취소 완료. data 에 안내 문구가 문자열로 들어온다.")
    @ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 작성자 본인이 아니거나 이미 처리된 지원서입니다.
            RESOURCE_NOT_FOUND — 지원서를 찾을 수 없습니다.""")
    @DeleteMapping("/applications/{applicationId}")
    public ResponseEntity<BaseResponse<String>> cancelApplication(
      @PathVariable Long applicationId,
      Authentication authentication
    ) {
        teamService.cancelApplication(applicationId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(BaseResponse.success("지원이 취소되었습니다."));
    }

    // 11. [NEW] 지원서 상세 조회 (팀장 OR 지원자 본인만 가능)
    @Operation(summary = "지원서 상세 조회", description = "지원 당사자이거나 해당 팀의 팀장이어야 한다(400 FORBIDDEN_ACCESS).")
    @ApiResponse(responseCode = "200", description = "지원서 상세.")
    @ApiResponse(responseCode = "400", description = """
            FORBIDDEN_ACCESS — 지원 당사자나 해당 팀의 팀장이 아닙니다.
            RESOURCE_NOT_FOUND — 지원서를 찾을 수 없습니다.""")
    @GetMapping("/applications/{applicationId}")
    public ResponseEntity<BaseResponse<TeamApplicationResponseDTO>> getApplicationDetail(
      @PathVariable Long applicationId,
      Authentication authentication
    ) {
        TeamApplicationResponseDTO response = teamService.getApplicationDetail(applicationId, Long.valueOf(authentication.getName()));
        return ResponseEntity.ok(BaseResponse.success(response));
    }
}

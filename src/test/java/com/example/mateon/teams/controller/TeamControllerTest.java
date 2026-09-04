package com.example.mateon.teams.controller;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.models.Event;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.teams.dto.response.TeamApplicationResponseDTO;
import com.example.mateon.teams.dto.response.TeamDetailResponseDTO;
import com.example.mateon.teams.dto.response.TeamResponseDTO;
import com.example.mateon.teams.service.CollaborationTemperatureCalculator;
import com.example.mateon.teams.service.TeamService;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 팀 모집글 API 의 전선 계약.
 *
 * <p>
 * 여기서 고정하는 것 중 셋은 다른 곳에서 확인할 수 없다.
 *
 * <p>
 * <b>하나, 익명 열람.</b> 목록과 상세는 토큰 없이도 동작하고 그때 서비스에
 * {@code userId = null} 이 넘어간다. 컨트롤러의 null 체크가 사라지면
 * {@code Long.valueOf(null)} 에서 NPE 가 나 <b>500</b> 이 되는데, 개발 중에는 늘 로그인 상태라
 * 아무도 밟지 않고 첫 방문자만 밟는다.
 *
 * <p>
 * <b>둘, 생성만 201 이다.</b> 이 코드베이스에서 201 을 쓰는 유일한 자리다. 나머지가 전부
 * 200 이라 "일관성"을 이유로 200 으로 맞추기 쉽다.
 *
 * <p>
 * <b>셋, 봉투가 두 모양이다.</b> 데이터를 돌려주는 엔드포인트는 {@code data} 에 담고
 * {@code message} 가 {@code "성공"} 인데, 데이터가 없는 엔드포인트({@code /apply},
 * {@code DELETE} 등)는 {@code ApiResponse.success("삭제되었습니다.")} 를 호출한다 — 인자가
 * 하나라 {@code success(T data)} 오버로드에 묶여 <b>사람이 읽을 문구가 {@code data} 로 간다</b>.
 * {@code AuthController} 에도 같은 일이 있다. 현재 나가는 모양을 그대로 못박는다 — 나중에
 * 봉투를 통일하면 이 단언들이 함께 빨개져서 프론트도 같이 고쳐야 함을 알린다.
 */
class TeamControllerTest {

    private static final long USER_ID = 1L;
    private static final long TEAM_ID = 7L;

    private TeamService teamService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        mockMvc = MockMvcBuilders
          .standaloneSetup(new TeamController(teamService))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();
    }

    @Nested
    @DisplayName("GET /api/teams — 비로그인 허용")
    class ListTeams {

        @Test
        @DisplayName("토큰이 없으면 userId 로 null 이 넘어간다 (NPE 로 500 이 되면 안 된다)")
        void anonymousPassesNullUserId() throws Exception {
            when(teamService.getTeams(isNull(), isNull(), eq(false), isNull())).thenReturn(List.of());

            mockMvc.perform(get("/api/teams"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true));

            verify(teamService).getTeams(null, null, false, null);
        }

        @Test
        @DisplayName("myPosts 기본값은 false 다")
        void myPostsDefaultsToFalse() throws Exception {
            when(teamService.getTeams(any(), any(), anyBoolean(), any())).thenReturn(List.of());

            mockMvc.perform(get("/api/teams").principal(auth()))
              .andExpect(status().isOk());

            verify(teamService).getTeams(null, null, false, USER_ID);
        }

        @Test
        @DisplayName("eventId/category/myPosts 는 그대로 넘어간다")
        void passesFilters() throws Exception {
            when(teamService.getTeams(any(), any(), anyBoolean(), any())).thenReturn(List.of());

            mockMvc.perform(get("/api/teams")
              .param("eventId", "3").param("category", "자율").param("myPosts", "true")
              .principal(auth()))
              .andExpect(status().isOk());

            verify(teamService).getTeams(3L, "자율", true, USER_ID);
        }

        @Test
        @DisplayName("모집 중 여부는 recruiting 과 isRecruiting 두 키로 같은 값이 나간다 (is 접두 통일 전환기)")
        void recruitingKeyName() throws Exception {
            when(teamService.getTeams(any(), any(), anyBoolean(), any()))
              .thenReturn(List.of(new TeamResponseDTO(team(), event(), 2)));

            mockMvc.perform(get("/api/teams"))
              .andExpect(jsonPath("$.data[0].recruiting").value(true))
              .andExpect(jsonPath("$.data[0].isRecruiting").value(true))
              .andExpect(jsonPath("$.data[0].id").value(7))
              .andExpect(jsonPath("$.data[0].currentMemberCount").value(2))
              .andExpect(jsonPath("$.data[0].connectedActivityTitle").value("교내 해커톤"));
        }

        @Test
        @DisplayName("팀이 없으면 빈 배열이다")
        void emptyList() throws Exception {
            when(teamService.getTeams(any(), any(), anyBoolean(), any())).thenReturn(List.of());

            mockMvc.perform(get("/api/teams"))
              .andExpect(jsonPath("$.data").isArray())
              .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("enum 이름이 아닌 category 는 400 이고 message 에 허용값이 실린다")
        void unknownCategoryIs400() throws Exception {
            when(teamService.getTeams(any(), eq("공모전"), anyBoolean(), any()))
              .thenThrow(new MateonException(ErrorCode.INVALID_INPUT,
                "'공모전' 는 허용되지 않는 category 값입니다. 가능한 값: 전체, 자율, CONTEST, EXTERNAL, SCHOOL, ETC"));

            mockMvc.perform(get("/api/teams").param("category", "공모전"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.success").value(false))
              .andExpect(jsonPath("$.message").value(
                "'공모전' 는 허용되지 않는 category 값입니다. 가능한 값: 전체, 자율, CONTEST, EXTERNAL, SCHOOL, ETC"));
        }
    }

    @Nested
    @DisplayName("GET /api/teams/{teamId}")
    class TeamDetail {

        @Test
        @DisplayName("비로그인 상세 조회도 열려 있고 userId 는 null 이다")
        void anonymousDetail() throws Exception {
            when(teamService.getTeamDetail(eq(TEAM_ID), isNull())).thenReturn(detail(false));

            mockMvc.perform(get("/api/teams/7"))
              .andExpect(status().isOk());

            verify(teamService).getTeamDetail(TEAM_ID, null);
        }

        /**
         * 한 응답 안에서 최상위는 {@code leader}(조회자가 팀장인가)와 {@code isLeader} 가 같은 값으로
         * 함께 나가고, 명단 한 줄은 {@code members[].isLeader}(그 사람이 팀장인가)만 나간다.
         * 최상위 이름이 둘인 이유는 is 접두 통일의 과도기라서다 — 프론트가 아직 {@code leader} 를
         * 읽고 있어 없앨 수 없고, 명단과 이름을 맞추려고 {@code isLeader} 를 더했다.
         */
        @Test
        @DisplayName("조회자 팀장 여부는 leader 와 isLeader 로 함께, 명단의 팀장 표시는 members[].isLeader 로 나간다")
        void twoDifferentLeaderKeys() throws Exception {
            when(teamService.getTeamDetail(anyLong(), any())).thenReturn(detail(true));

            mockMvc.perform(get("/api/teams/7").principal(auth()))
              .andExpect(jsonPath("$.data.leader").value(true))
              .andExpect(jsonPath("$.data.isLeader").value(true))
              .andExpect(jsonPath("$.data.members[0].isLeader").value(true))
              .andExpect(jsonPath("$.data.members[0].leader").doesNotExist());
        }

        @Test
        @DisplayName("종료 여부는 isEnded 로 나간다 (@JsonProperty 가 붙어 있어 recruiting 과 규칙이 다르다)")
        void isEndedKeepsPrefix() throws Exception {
            when(teamService.getTeamDetail(anyLong(), any())).thenReturn(detail(false));

            mockMvc.perform(get("/api/teams/7"))
              .andExpect(jsonPath("$.data.isEnded").value(false))
              .andExpect(jsonPath("$.data.ended").doesNotExist());
        }

        @Test
        @DisplayName("팀장 협업 온도와 지원 여부가 함께 내려간다")
        void carriesLeaderInfo() throws Exception {
            when(teamService.getTeamDetail(anyLong(), any())).thenReturn(detail(false));

            mockMvc.perform(get("/api/teams/7"))
              .andExpect(jsonPath("$.data.leaderName").value("팀장"))
              .andExpect(jsonPath("$.data.leaderCollaborationTemperature").value(36.5))
              .andExpect(jsonPath("$.data.hasApplied").value(false))
              .andExpect(jsonPath("$.data.currentMemberCount").value(1));
        }

        @Test
        @DisplayName("없는 팀은 400 이다 (RESOURCE_NOT_FOUND 는 404 가 아니다)")
        void missingTeamIs400() throws Exception {
            when(teamService.getTeamDetail(anyLong(), any()))
              .thenThrow(new MateonException(ErrorCode.RESOURCE_NOT_FOUND));

            mockMvc.perform(get("/api/teams/7"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.RESOURCE_NOT_FOUND.getMessage()));
        }

        @Test
        @DisplayName("teamId 가 숫자가 아니면 400 이고 서비스까지 가지 않는다")
        void nonNumericPathIs400() throws Exception {
            mockMvc.perform(get("/api/teams/abc"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."));

            verifyNoInteractions(teamService);
        }
    }

    @Nested
    @DisplayName("POST /api/teams")
    class CreateTeam {

        @Test
        @DisplayName("생성만 201 이다 (코드베이스에서 유일하다 — 200 으로 맞추지 말 것)")
        void createdIs201() throws Exception {
            when(teamService.createTeam(any(), eq(USER_ID)))
              .thenReturn(new TeamResponseDTO(team(), null, 1));

            mockMvc.perform(post("/api/teams")
              .contentType(MediaType.APPLICATION_JSON)
              .content(createBody())
              .principal(auth()))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.data.id").value(7))
              .andExpect(jsonPath("$.data.currentMemberCount").value(1));
        }

        @Test
        @DisplayName("제목·역할·모집 기간이 없으면 서비스까지 가지 않고 400 이다")
        void validationBlocksBeforeService() throws Exception {
            mockMvc.perform(post("/api/teams")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}")
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."))
              .andExpect(jsonPath("$.data.title").value("모집글 제목은 필수입니다."))
              .andExpect(jsonPath("$.data.role").value("모집 역할은 필수입니다."))
              .andExpect(jsonPath("$.data.recruitmentStartDate").value("모집 시작일은 필수입니다."));

            verifyNoInteractions(teamService);
        }

        @Test
        @DisplayName("모집 인원 0명은 거절된다")
        void capacityMustBeAtLeastOne() throws Exception {
            mockMvc.perform(post("/api/teams")
              .contentType(MediaType.APPLICATION_JSON)
              .content(createBody().replace("\"capacity\":4", "\"capacity\":0"))
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.data.capacity").value("모집 인원은 최소 1명 이상이어야 합니다."));
        }

        @Test
        @DisplayName("학교 인증 전이면 400 SCHOOL_NOT_VERIFIED 다")
        void schoolNotVerified() throws Exception {
            when(teamService.createTeam(any(), anyLong()))
              .thenThrow(new MateonException(ErrorCode.SCHOOL_NOT_VERIFIED));

            mockMvc.perform(post("/api/teams")
              .contentType(MediaType.APPLICATION_JSON)
              .content(createBody())
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.SCHOOL_NOT_VERIFIED.getMessage()));
        }

        @Test
        @DisplayName("날짜 형식이 틀리면 400 이다 (본문 파싱 실패도 같은 봉투다)")
        void badDateFormat() throws Exception {
            mockMvc.perform(post("/api/teams")
              .contentType(MediaType.APPLICATION_JSON)
              .content(createBody().replace("\"2026-01-01\"", "\"어제\""))
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."));

            verifyNoInteractions(teamService);
        }
    }

    @Nested
    @DisplayName("수정·삭제")
    class UpdateAndDelete {

        @Test
        @DisplayName("수정은 200 이고 갱신된 팀을 돌려준다")
        void updateReturnsTeam() throws Exception {
            when(teamService.updateTeam(eq(TEAM_ID), any(), eq(USER_ID)))
              .thenReturn(new TeamResponseDTO(team(), null, 2));

            mockMvc.perform(put("/api/teams/7")
              .contentType(MediaType.APPLICATION_JSON)
              .content(createBody())
              .principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.id").value(7));
        }

        @Test
        @DisplayName("팀장이 아니면 403 이 아니라 400 이다")
        void forbiddenIs400() throws Exception {
            when(teamService.updateTeam(anyLong(), any(), anyLong()))
              .thenThrow(new MateonException(ErrorCode.FORBIDDEN_ACCESS));

            mockMvc.perform(put("/api/teams/7")
              .contentType(MediaType.APPLICATION_JSON)
              .content(createBody())
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.FORBIDDEN_ACCESS.getMessage()));
        }

        /**
         * {@code ApiResponse.success("삭제되었습니다.")} 는 인자가 하나라
         * {@code success(T data)} 에 묶인다. 그래서 안내 문구가 {@code data} 로 가고
         * {@code message} 는 {@code "성공"} 이 된다. 현재 나가는 모양이다.
         */
        @Test
        @DisplayName("삭제 응답은 안내 문구가 message 가 아니라 data 에 담긴다")
        void deleteMessageLandsInData() throws Exception {
            mockMvc.perform(delete("/api/teams/7").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.message").value("성공"))
              .andExpect(jsonPath("$.data").value("삭제되었습니다."));

            verify(teamService).deleteTeam(TEAM_ID, USER_ID);
        }
    }

    @Nested
    @DisplayName("지원 흐름")
    class Applications {

        @Test
        @DisplayName("지원 응답도 문구가 data 에 담긴다")
        void applyMessageLandsInData() throws Exception {
            mockMvc.perform(post("/api/teams/7/apply")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"message\":\"지원 동기\",\"contactNumber\":\"010-0000-0000\"}")
              .principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data").value("지원이 완료되었습니다."));

            verify(teamService).applyToTeam(eq(TEAM_ID), any(), eq(USER_ID));
        }

        @Test
        @DisplayName("지원 동기와 연락처는 필수다")
        void applyValidation() throws Exception {
            mockMvc.perform(post("/api/teams/7/apply")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}")
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.data.message").value("지원 동기는 필수입니다."))
              .andExpect(jsonPath("$.data.contactNumber").value("연락처는 필수입니다."));

            verify(teamService, never()).applyToTeam(anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("본인 팀 지원 같은 IllegalArgumentException 은 그 문장이 message 로 나간다")
        void illegalArgumentMessageIsExposed() throws Exception {
            org.mockito.Mockito.doThrow(new IllegalArgumentException("이미 지원한 팀입니다."))
              .when(teamService).applyToTeam(anyLong(), any(), anyLong());

            mockMvc.perform(post("/api/teams/7/apply")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"message\":\"지원 동기\",\"contactNumber\":\"010-0000-0000\"}")
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("이미 지원한 팀입니다."));
        }

        @Test
        @DisplayName("승인/거절은 isApproved 쿼리 파라미터로 갈리고 문구가 달라진다")
        void processApplication() throws Exception {
            mockMvc.perform(patch("/api/teams/applications/30")
              .param("isApproved", "true")
              .principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data").value("승인되었습니다."));

            mockMvc.perform(patch("/api/teams/applications/30")
              .param("isApproved", "false")
              .principal(auth()))
              .andExpect(jsonPath("$.data").value("거절되었습니다."));

            verify(teamService).processApplication(30L, true, USER_ID);
            verify(teamService).processApplication(30L, false, USER_ID);
        }

        @Test
        @DisplayName("이미 처리된 지원서는 400 APPLICATION_ALREADY_PROCESSED 다")
        void alreadyProcessed() throws Exception {
            org.mockito.Mockito.doThrow(new MateonException(ErrorCode.APPLICATION_ALREADY_PROCESSED))
              .when(teamService).processApplication(anyLong(), anyBoolean(), anyLong());

            mockMvc.perform(patch("/api/teams/applications/30")
              .param("isApproved", "true")
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message")
                .value(ErrorCode.APPLICATION_ALREADY_PROCESSED.getMessage()));
        }

        @Test
        @DisplayName("내 지원서 목록은 principal 의 userId 로 조회한다")
        void myApplications() throws Exception {
            when(teamService.getMyApplications(USER_ID)).thenReturn(List.of());

            mockMvc.perform(get("/api/teams/applications/me").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data").isArray());

            verify(teamService).getMyApplications(USER_ID);
        }

        @Test
        @DisplayName("팀별 지원서 목록은 teamId 와 요청자를 함께 넘긴다 (권한 검사가 서비스에 있다)")
        void teamApplications() throws Exception {
            when(teamService.getApplicationsForMyTeam(TEAM_ID, USER_ID)).thenReturn(List.of());

            mockMvc.perform(get("/api/teams/7/applications").principal(auth()))
              .andExpect(status().isOk());

            verify(teamService).getApplicationsForMyTeam(TEAM_ID, USER_ID);
        }

        @Test
        @DisplayName("지원서 상세는 제3자에게 400 FORBIDDEN_ACCESS 다")
        void detailForbidden() throws Exception {
            when(teamService.getApplicationDetail(anyLong(), anyLong()))
              .thenThrow(new MateonException(ErrorCode.FORBIDDEN_ACCESS));

            mockMvc.perform(get("/api/teams/applications/30").principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.FORBIDDEN_ACCESS.getMessage()));
        }

        @Test
        @DisplayName("지원서 상세 성공은 서비스 결과를 data 에 담는다")
        void detailOk() throws Exception {
            when(teamService.getApplicationDetail(30L, USER_ID))
              .thenReturn(TeamApplicationResponseDTO.builder().applicationId(30L).build());

            mockMvc.perform(get("/api/teams/applications/30").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.applicationId").value(30));
        }

        @Test
        @DisplayName("지원서 수정·취소도 문구가 data 에 담긴다")
        void editAndCancelMessages() throws Exception {
            mockMvc.perform(put("/api/teams/applications/30")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"message\":\"바뀐 동기\",\"contactNumber\":\"010-1111-2222\"}")
              .principal(auth()))
              .andExpect(jsonPath("$.data").value("지원서가 수정되었습니다."));

            mockMvc.perform(delete("/api/teams/applications/30").principal(auth()))
              .andExpect(jsonPath("$.data").value("지원이 취소되었습니다."));

            verify(teamService).updateApplication(eq(30L), any(), eq(USER_ID));
            verify(teamService).cancelApplication(30L, USER_ID);
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    private Team team() {
        Team team = new Team();
        team.setId(TEAM_ID);
        team.setTitle("백엔드 구합니다");
        team.setRole(List.of("백엔드"));
        team.setRequiredSkills(List.of("Spring"));
        team.setCapacity(4);
        team.setEventId(3L);
        team.setLeaderUserId(USER_ID);
        team.setRecruitmentStartDate(LocalDate.of(2026, 1, 1));
        team.setRecruitmentEndDate(LocalDate.of(2026, 12, 31));
        return team;
    }

    private Event event() {
        Event event = new Event();
        event.setId(3L);
        event.setTitle("교내 해커톤");
        event.setSummarizedDescription("이틀간 진행");
        return event;
    }

    private TeamDetailResponseDTO detail(boolean isLeader) {
        User leader = User.builder().id(USER_ID).name("팀장").major("컴퓨터공학").grade("3").build();
        List<TeamMember> members = List.of(TeamMember.of(team(), leader, TeamMemberRole.LEADER));
        return new TeamDetailResponseDTO(team(), event(), members, isLeader, null, leader,
          CollaborationTemperatureCalculator.INITIAL);
    }

    private String createBody() {
        return """
                {"title":"백엔드 구합니다","role":["백엔드"],"requiredSkills":["Spring"],
                 "capacity":4,"promotionText":"같이 해요","characteristic":"온라인",
                 "recruitmentStartDate":"2026-01-01","recruitmentEndDate":"2026-12-31"}
                """;
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }
}

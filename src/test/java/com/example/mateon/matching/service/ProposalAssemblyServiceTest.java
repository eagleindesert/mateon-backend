package com.example.mateon.matching.service;

import com.example.mateon.matching.client.proposal.ProposalAssemblyRequest;
import com.example.mateon.matching.client.proposal.ProposalClient;
import com.example.mateon.matching.client.proposal.ProposalResponse;
import com.example.mateon.matching.dto.response.ProposalDraftResponseDTO;
import com.example.mateon.matching.dto.snapshot.ProposalSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 제안 문구 조립. 방향에 따라 <b>sender/receiver 자리가 뒤바뀐다</b>는 것이 전부이자 함정이다.
 *
 * <p>
 * {@code userId} 와 {@code teamId} 는 방향과 무관하게 고정 자리에 들어가지만,
 * {@code sender_id}/{@code receiver_id} 는 서로 자리를 바꾼다. 잘못 넣으면 AI 는 아무 불평 없이
 * "팀이 유저에게 쓰는 말투"로 유저의 지원서를 써 준다 — 화면에 문장이 뜨긴 하므로 에러는 없고,
 * 사용자가 읽고 이상하다고 느낄 뿐이다.
 *
 * <p>
 * 또 하나는 <b>synergyScore 의 출처</b>다. AI 응답에도 같은 이름의 필드가 있지만 우리는
 * 추천 이력에서 읽은 값을 쓴다. 출처가 둘이면 화면과 DB 의 숫자가 달라진다.
 *
 * <p>
 * 이 서비스에는 저장 단계가 없다 — 초안은 사용자가 고쳐서 기존 발송 API 로 보낸다.
 * 그래서 부수효과가 없다는 것도 함께 확인한다.
 */
class ProposalAssemblyServiceTest {

    private static final long USER_ID = 1L;
    private static final long TEAM_ID = 100L;

    private RecommendationQueryService queryService;
    private ProposalClient client;
    private ProposalAssemblyService service;

    @BeforeEach
    void setUp() {
        queryService = mock(RecommendationQueryService.class);
        client = mock(ProposalClient.class);
        service = new ProposalAssemblyService(queryService, client);
    }

    @Nested
    @DisplayName("유저 → 팀 (지원 문구)")
    class UserToTeam {

        @Test
        @DisplayName("direction 은 USER_TO_TEAM 이고 sender 가 유저, receiver 가 팀이다")
        void senderIsUser() {
            when(queryService.gatherProposalForUserToTeam(USER_ID, TEAM_ID)).thenReturn(snapshot());
            when(client.userToTeam(any())).thenReturn(proposal("요약", "본문", 0.11));

            ProposalDraftResponseDTO draft = service.draftForTeam(USER_ID, TEAM_ID);

            assertThat(draft.getDirection()).isEqualTo("USER_TO_TEAM");

            ProposalAssemblyRequest request = captureUserToTeamRequest();
            assertThat(request.getSenderId()).isEqualTo(USER_ID);
            assertThat(request.getReceiverId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("팀→유저 경로는 부르지 않는다")
        void doesNotCallOtherDirection() {
            when(queryService.gatherProposalForUserToTeam(USER_ID, TEAM_ID)).thenReturn(snapshot());
            when(client.userToTeam(any())).thenReturn(proposal("요약", "본문", 0.9));

            service.draftForTeam(USER_ID, TEAM_ID);

            verify(client, never()).teamToUser(any());
        }
    }

    @Nested
    @DisplayName("팀 → 유저 (역제안 문구)")
    class TeamToUser {

        @Test
        @DisplayName("direction 은 TEAM_TO_USER 이고 sender/receiver 가 정확히 반대다")
        void senderIsTeam() {
            when(queryService.gatherProposalForTeamToUser(TEAM_ID, USER_ID, 7L)).thenReturn(snapshot());
            when(client.teamToUser(any())).thenReturn(proposal("요약", "본문", 0.11));

            ProposalDraftResponseDTO draft = service.draftForUser(TEAM_ID, USER_ID, 7L);

            assertThat(draft.getDirection()).isEqualTo("TEAM_TO_USER");

            ArgumentCaptor<ProposalAssemblyRequest> captor
              = ArgumentCaptor.forClass(ProposalAssemblyRequest.class);
            verify(client).teamToUser(captor.capture());
            assertThat(captor.getValue().getSenderId()).isEqualTo(TEAM_ID);
            assertThat(captor.getValue().getReceiverId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("요청자(팀장) id 가 조회로 넘어간다 — 권한 검사가 거기서 일어난다")
        void passesRequesterForAuthorization() {
            when(queryService.gatherProposalForTeamToUser(TEAM_ID, USER_ID, 7L)).thenReturn(snapshot());
            when(client.teamToUser(any())).thenReturn(proposal("요약", "본문", 0.9));

            service.draftForUser(TEAM_ID, USER_ID, 7L);

            verify(queryService).gatherProposalForTeamToUser(TEAM_ID, USER_ID, 7L);
        }
    }

    @Test
    @DisplayName("userId/teamId 는 방향과 무관하게 같은 자리다 (뒤바뀌는 건 sender/receiver 뿐이다)")
    void idsStayInFixedSlots() {
        when(queryService.gatherProposalForUserToTeam(USER_ID, TEAM_ID)).thenReturn(snapshot());
        when(client.userToTeam(any())).thenReturn(proposal("요약", "본문", 0.9));

        ProposalAssemblyRequest request;
        service.draftForTeam(USER_ID, TEAM_ID);
        request = captureUserToTeamRequest();

        assertThat(request.getUserId()).isEqualTo(USER_ID);
        assertThat(request.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(request.getContestId()).isEqualTo(7L);
        assertThat(request.getIntentId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("synergyScore 는 AI 응답이 아니라 추천 이력의 값을 쓴다 (출처를 하나로 고정한다)")
    void synergyScoreComesFromSnapshotNotAi() {
        when(queryService.gatherProposalForUserToTeam(USER_ID, TEAM_ID)).thenReturn(snapshot());
        // AI 가 전혀 다른 숫자를 돌려줘도 무시한다.
        when(client.userToTeam(any())).thenReturn(proposal("요약", "본문", 0.11));

        ProposalDraftResponseDTO draft = service.draftForTeam(USER_ID, TEAM_ID);

        assertThat(draft.getSynergyScore()).isEqualTo(0.87);
        // 요청에도 우리가 읽은 값이 실린다.
        assertThat(captureUserToTeamRequest().getSynergyScore()).isEqualTo(0.87);
    }

    @Test
    @DisplayName("요약 두 개는 조회 단계에서 이미 방향에 맞게 배치돼 온 것을 그대로 전달한다")
    void passesSummariesThrough() {
        when(queryService.gatherProposalForUserToTeam(USER_ID, TEAM_ID)).thenReturn(snapshot());
        when(client.userToTeam(any())).thenReturn(proposal("요약", "본문", 0.9));

        service.draftForTeam(USER_ID, TEAM_ID);

        ProposalAssemblyRequest request = captureUserToTeamRequest();
        assertThat(request.getCandidateSummary()).isEqualTo("후보 요약");
        assertThat(request.getTargetSummary()).isEqualTo("대상 요약");
    }

    @Test
    @DisplayName("AI 가 쓴 문구가 응답에 그대로 담긴다 (저장 단계는 없다)")
    void returnsDraftWithoutPersisting() {
        when(queryService.gatherProposalForUserToTeam(USER_ID, TEAM_ID)).thenReturn(snapshot());
        when(client.userToTeam(any())).thenReturn(proposal("한 줄 요약", "안녕하세요, 지원합니다.", 0.9));

        ProposalDraftResponseDTO draft = service.draftForTeam(USER_ID, TEAM_ID);

        assertThat(draft.getSummary()).isEqualTo("한 줄 요약");
        assertThat(draft.getMessage()).isEqualTo("안녕하세요, 지원합니다.");
        assertThat(draft.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(draft.getUserId()).isEqualTo(USER_ID);
    }

    // --- 픽스처 -------------------------------------------------------------
    private ProposalAssemblyRequest captureUserToTeamRequest() {
        ArgumentCaptor<ProposalAssemblyRequest> captor
          = ArgumentCaptor.forClass(ProposalAssemblyRequest.class);
        verify(client).userToTeam(captor.capture());
        return captor.getValue();
    }

    private ProposalSnapshot snapshot() {
        return new ProposalSnapshot(USER_ID, TEAM_ID, 7L, 55L, 0.87, "후보 요약", "대상 요약");
    }

    private ProposalResponse proposal(String summary, String message, Double synergyScore) {
        ProposalResponse response = new ProposalResponse();
        ReflectionTestUtils.setField(response, "summary", summary);
        ReflectionTestUtils.setField(response, "message", message);
        ReflectionTestUtils.setField(response, "synergyScore", synergyScore);
        return response;
    }
}

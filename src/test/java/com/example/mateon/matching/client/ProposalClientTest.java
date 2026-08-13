package com.example.mateon.matching.client;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 제안 문구 조립 클라이언트.
 *
 * <p>검증할 게 둘뿐이다. <b>경로</b>(두 방향이 스키마가 같아 뒤바뀌어도 에러가 나지 않는다)와
 * <b>빈 초안 거부</b>다.
 *
 * <p>빈 초안 거부가 중요한 이유: 화면에 빈 입력창을 띄우고 "AI 가 작성했습니다" 라고 말하는
 * 것보다 실패가 낫다. summary 와 message 중 <b>하나만</b> 비어도 거부해야 하는데, 조건을
 * {@code &&} 로 잘못 쓰면 반쪽짜리 초안이 그대로 나간다.
 */
class ProposalClientTest {

    private AiCallTemplate ai;
    private ProposalClient client;

    @BeforeEach
    void setUp() {
        ai = mock(AiCallTemplate.class);
        client = new ProposalClient(ai);
    }

    @Test
    @DisplayName("유저→팀은 /proposals/user-to-team 으로 간다")
    void userToTeamPath() {
        when(ai.post(eq("/proposals/user-to-team"), any(), eq(ProposalResponse.class)))
                .thenReturn(proposal("요약", "본문", 0.87));

        ProposalResponse response = client.userToTeam(request());

        assertThat(response.getSummary()).isEqualTo("요약");
        assertThat(response.getMessage()).isEqualTo("본문");
        assertThat(response.getSynergyScore()).isEqualTo(0.87);
        verify(ai).post(eq("/proposals/user-to-team"), any(), eq(ProposalResponse.class));
    }

    @Test
    @DisplayName("팀→유저는 /proposals/team-to-user 로 간다")
    void teamToUserPath() {
        when(ai.post(eq("/proposals/team-to-user"), any(), eq(ProposalResponse.class)))
                .thenReturn(proposal("요약", "본문", 0.5));

        client.teamToUser(request());

        verify(ai).post(eq("/proposals/team-to-user"), any(), eq(ProposalResponse.class));
    }

    @Test
    @DisplayName("summary 만 비어도 거부한다 (한쪽만 검사하면 반쪽짜리 초안이 나간다)")
    void blankSummaryIsRejected() {
        when(ai.post(any(), any(), eq(ProposalResponse.class))).thenReturn(proposal("  ", "본문", 0.5));

        assertProposalRejected();
    }

    @Test
    @DisplayName("message 만 비어도 거부한다")
    void blankMessageIsRejected() {
        when(ai.post(any(), any(), eq(ProposalResponse.class))).thenReturn(proposal("요약", null, 0.5));

        assertProposalRejected();
    }

    @Test
    @DisplayName("둘 다 비면 당연히 거부한다")
    void bothBlankIsRejected() {
        when(ai.post(any(), any(), eq(ProposalResponse.class))).thenReturn(proposal(null, null, null));

        assertProposalRejected();
    }

    private void assertProposalRejected() {
        assertThatThrownBy(() -> client.userToTeam(request()))
                .isInstanceOf(MateonException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_ERROR);
    }

    private ProposalResponse proposal(String summary, String message, Double synergyScore) {
        ProposalResponse response = new ProposalResponse();
        ReflectionTestUtils.setField(response, "summary", summary);
        ReflectionTestUtils.setField(response, "message", message);
        ReflectionTestUtils.setField(response, "synergyScore", synergyScore);
        return response;
    }

    private ProposalAssemblyRequest request() {
        return new ProposalAssemblyRequest(1L, 2L, 3L, 1L, 2L, 4L, 0.9, "후보 요약", "대상 요약");
    }
}

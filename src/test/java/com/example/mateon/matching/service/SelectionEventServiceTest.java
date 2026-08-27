package com.example.mateon.matching.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.client.selection.SelectionEventClient;
import com.example.mateon.matching.client.selection.SelectionEventRequest;
import com.example.mateon.matching.domain.SelectionDirection;
import com.example.mateon.matching.dto.snapshot.SelectionSnapshot;
import com.example.mateon.matching.event.CandidateSelectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 선택 피드백 오케스트레이터의 규약을 고정한다.
 *
 * <p>
 * 여기서 지키려는 건 세 가지다:
 * <ul>
 *   <li><b>추천을 안 거친 지원/제안은 조용히 넘어간다.</b> 이게 깨지면 정상 발송마다 AI 호출이
 *       한 번씩 헛돌거나 예외가 난다 — 추천 없이 지원하는 건 막지 않는 경로다.</li>
 *   <li><b>선택 표시가 AI 호출보다 먼저다.</b> 순서가 뒤집히면 AI 장애가 곧 선택 기록의
 *       소실이 되고, 그건 나중에 재전송할 근거까지 사라진다는 뜻이다.</li>
 *   <li><b>멱등키가 결정적이다.</b> 랜덤이면 재시도가 중복 이벤트가 된다.</li>
 * </ul>
 */
class SelectionEventServiceTest {

    private RecommendationQueryService queryService;
    private RecommendationLogService logService;
    private SelectionEventClient client;
    private SelectionEventService service;

    @BeforeEach
    void setUp() {
        queryService = mock(RecommendationQueryService.class);
        logService = mock(RecommendationLogService.class);
        client = mock(SelectionEventClient.class);
        service = new SelectionEventService(queryService, logService, client);
    }

    @Nested
    @DisplayName("추천 이력이 없을 때")
    class NoRecommendationHistory {

        @Test
        @DisplayName("AI 를 부르지 않는다 — 추천을 거치지 않은 지원/제안은 기록할 선택이 없다")
        void skipsSilently() {
            when(queryService.gatherSelection(any(), anyLong(), anyLong()))
              .thenReturn(Optional.empty());

            service.record(CandidateSelectedEvent.userToTeam(1L, 10L, 500L));

            verify(client, never()).send(any());
            verify(logService, never()).markSelected(any(), anyLong());
        }
    }

    @Nested
    @DisplayName("순서")
    class Ordering {

        @Test
        @DisplayName("선택 표시가 AI 전송보다 먼저다 (AI 가 죽어도 선택은 우리 DB 에 남는다)")
        void marksBeforeSending() {
            givenSnapshot(userToTeamSnapshot());

            service.record(CandidateSelectedEvent.userToTeam(1L, 17L, 500L));

            InOrder order = inOrder(logService, client);
            order.verify(logService).markSelected(SelectionDirection.USER_TO_TEAM, 900L);
            order.verify(client).send(any());
        }

        @Test
        @DisplayName("AI 전송이 실패해도 선택 표시는 이미 끝나 있다")
        void markSurvivesAiFailure() {
            givenSnapshot(userToTeamSnapshot());
            org.mockito.Mockito.doThrow(new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE))
              .when(client).send(any());

            assertThatThrownBy(() -> service.record(CandidateSelectedEvent.userToTeam(1L, 17L, 500L)))
              .isInstanceOf(MateonException.class);

            verify(logService).markSelected(SelectionDirection.USER_TO_TEAM, 900L);
        }
    }

    @Nested
    @DisplayName("요청 조립")
    class RequestAssembly {

        @Test
        @DisplayName("노출됐던 후보가 점수와 원문 component_scores 그대로 실린다")
        void carriesShownCandidates() {
            givenSnapshot(userToTeamSnapshot());

            service.record(CandidateSelectedEvent.userToTeam(1L, 17L, 500L));

            SelectionEventRequest sent = captureRequest();
            assertThat(sent.getDirection()).isEqualTo(SelectionDirection.USER_TO_TEAM);
            assertThat(sent.getSelectedCandidateId()).isEqualTo(17L);
            assertThat(sent.getSelectionContext().getShownCandidates()).hasSize(2);

            SelectionEventRequest.ShownCandidate first
              = sent.getSelectionContext().getShownCandidates().get(0);
            assertThat(first.getCandidateId()).isEqualTo(17L);
            assertThat(first.getTotalScore()).isEqualTo(0.92);
            assertThat(first.getComponentScores()).isEqualTo("{\"similarity\":0.8}");
        }

        @Test
        @DisplayName("chooser_fields 는 조회 단계가 만든 그대로 넘어간다")
        void passesChooserFieldsThrough() {
            givenSnapshot(userToTeamSnapshot());

            service.record(CandidateSelectedEvent.userToTeam(1L, 17L, 500L));

            assertThat(captureRequest().getSelectionContext().getChooserFields())
              .containsEntry("experience_level", "beginner");
        }
    }

    @Nested
    @DisplayName("멱등키")
    class IdempotencyKey {

        @Test
        @DisplayName("같은 선택은 항상 같은 키를 만든다 (재시도가 중복 이벤트가 되지 않는다)")
        void isDeterministic() {
            givenSnapshot(userToTeamSnapshot());

            service.record(CandidateSelectedEvent.userToTeam(1L, 17L, 500L));
            service.record(CandidateSelectedEvent.userToTeam(1L, 17L, 500L));

            ArgumentCaptor<SelectionEventRequest> captor
              = ArgumentCaptor.forClass(SelectionEventRequest.class);
            verify(client, org.mockito.Mockito.times(2)).send(captor.capture());

            assertThat(captor.getAllValues().get(0).getSelectionContext().getIdempotencyKey())
              .isEqualTo(captor.getAllValues().get(1).getSelectionContext().getIdempotencyKey());
        }

        @Test
        @DisplayName("지원서 7번과 제안 7번은 다른 키다 (방향이 키에 섞여 있다)")
        void differsByDirection() {
            givenSnapshot(userToTeamSnapshot());
            service.record(CandidateSelectedEvent.userToTeam(1L, 17L, 7L));
            String userToTeamKey = captureRequest().getSelectionContext().getIdempotencyKey();

            org.mockito.Mockito.reset(client);
            givenSnapshot(teamToUserSnapshot());
            service.record(CandidateSelectedEvent.teamToUser(100L, 203L, 7L));
            String teamToUserKey = captureRequest().getSelectionContext().getIdempotencyKey();

            assertThat(userToTeamKey).isNotEqualTo(teamToUserKey);
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private void givenSnapshot(SelectionSnapshot snapshot) {
        when(queryService.gatherSelection(any(), anyLong(), anyLong()))
          .thenReturn(Optional.of(snapshot));
    }

    private SelectionEventRequest captureRequest() {
        ArgumentCaptor<SelectionEventRequest> captor
          = ArgumentCaptor.forClass(SelectionEventRequest.class);
        verify(client).send(captor.capture());
        return captor.getValue();
    }

    private SelectionSnapshot userToTeamSnapshot() {
        return new SelectionSnapshot(SelectionDirection.USER_TO_TEAM, 17L, 900L,
          Map.of("experience_level", "beginner"),
          List.of(new SelectionSnapshot.ShownCandidate(17L, 0.92, "{\"similarity\":0.8}"),
            new SelectionSnapshot.ShownCandidate(42L, 0.26, "{\"similarity\":0.1}")));
    }

    private SelectionSnapshot teamToUserSnapshot() {
        return new SelectionSnapshot(SelectionDirection.TEAM_TO_USER, 203L, 901L,
          Map.of("contest_field", "EDUCATION"),
          List.of(new SelectionSnapshot.ShownCandidate(203L, 0.908, "{\"similarity\":0.77}")));
    }
}

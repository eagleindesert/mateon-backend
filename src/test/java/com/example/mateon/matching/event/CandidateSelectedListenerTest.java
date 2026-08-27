package com.example.mateon.matching.event;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.service.SelectionEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 선택 피드백 리스너가 <b>어떤 실패도 밖으로 내보내지 않는지</b>를 고정한다.
 *
 * <p>
 * 이 클래스에 로직이랄 게 없는데도 테스트가 있는 이유가 그것이다. AiCallTemplate 은 AI 서버가
 * 죽어 있으면 MateonException 을 던지고, 이 리스너는 지원/제안 트랜잭션이 커밋된 <b>뒤에</b>
 * 돈다. 여기서 예외가 새면 사용자는 이미 지원에 성공했는데 스레드에는 실패 스택이 쌓인다 —
 * 명세도 "로깅 실패가 제안 생성이나 사용자 화면 흐름을 막아서는 안 된다"고 못박고 있다.
 *
 * <p>
 * 이건 try/catch 하나만 지우면 조용히 깨지고, 통합 테스트로는 AI 장애 상황을 만들기 어렵다.
 * 그래서 여기서 잠근다.
 */
class CandidateSelectedListenerTest {

    private SelectionEventService selectionEventService;
    private CandidateSelectedListener listener;

    @BeforeEach
    void setUp() {
        selectionEventService = mock(SelectionEventService.class);
        listener = new CandidateSelectedListener(selectionEventService);
    }

    @Test
    @DisplayName("이벤트를 그대로 서비스에 넘긴다")
    void delegatesEvent() {
        CandidateSelectedEvent event = CandidateSelectedEvent.userToTeam(1L, 17L, 500L);

        listener.onCandidateSelected(event);

        ArgumentCaptor<CandidateSelectedEvent> captor
          = ArgumentCaptor.forClass(CandidateSelectedEvent.class);
        verify(selectionEventService).record(captor.capture());
        assertThat(captor.getValue()).isEqualTo(event);
    }

    @Test
    @DisplayName("AI 장애를 밖으로 내보내지 않는다 (지원/제안은 이미 커밋됐다)")
    void swallowsAiFailure() {
        doThrow(new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE))
          .when(selectionEventService).record(any());

        assertThatCode(() -> listener.onCandidateSelected(
          CandidateSelectedEvent.userToTeam(1L, 17L, 500L)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("예상 못 한 예외도 삼킨다 (DB 장애든 NPE 든 사용자 흐름과 무관하다)")
    void swallowsUnexpectedFailure() {
        doThrow(new RuntimeException("DB 다운")).when(selectionEventService).record(any());

        assertThatCode(() -> listener.onCandidateSelected(
          CandidateSelectedEvent.teamToUser(100L, 203L, 700L)))
          .doesNotThrowAnyException();
    }
}

package com.example.mateon.events.event;

import com.example.mateon.events.service.EventEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 활동 등록 커밋 뒤 임베딩 갱신이 HTTP 응답을 붙잡지 않고, 실패해도 등록을 되돌리지 않는지
 * 고정한다.
 *
 * <p>
 * 리스너가 예외를 그대로 올리면 비동기 스레드가 죽고, 다음 백필 전까지 그 활동은 유사도 지도에서
 * 빠진다. warn 만 남기고 삼키는 게 이 클래스의 계약이다.
 */
class EventEmbeddingRefreshListenerTest {

    private static final long EVENT_ID = 42L;

    private EventEmbeddingService eventEmbeddingService;
    private EventEmbeddingRefreshListener listener;

    @BeforeEach
    void setUp() {
        eventEmbeddingService = mock(EventEmbeddingService.class);
        listener = new EventEmbeddingRefreshListener(eventEmbeddingService);
    }

    @Test
    @DisplayName("커밋 신호의 eventId 로 임베딩을 갱신한다")
    void refreshesByEventId() {
        listener.onRefreshRequested(new EventEmbeddingRefreshRequestedEvent(EVENT_ID));

        verify(eventEmbeddingService).refresh(EVENT_ID);
    }

    @Test
    @DisplayName("갱신 실패는 삼킨다 — AI 장애가 활동 등록을 되돌리면 안 된다")
    void swallowsRefreshFailure() {
        doThrow(new RuntimeException("AI 다운"))
          .when(eventEmbeddingService).refresh(EVENT_ID);

        assertThatCode(() -> listener.onRefreshRequested(
          new EventEmbeddingRefreshRequestedEvent(EVENT_ID)))
          .doesNotThrowAnyException();
    }
}

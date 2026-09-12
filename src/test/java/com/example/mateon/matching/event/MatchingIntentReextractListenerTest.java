package com.example.mateon.matching.event;

import com.example.mateon.matching.service.MatchingIntentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MatchingIntentReextractListenerTest {

    private static final long USER_ID = 7L;

    private MatchingIntentService matchingIntentService;
    private MatchingIntentReextractListener listener;

    @BeforeEach
    void setUp() {
        matchingIntentService = mock(MatchingIntentService.class);
        listener = new MatchingIntentReextractListener(matchingIntentService);
    }

    @Test
    @DisplayName("커밋 후 이벤트의 userId 로 재추출을 위임한다")
    void delegates() {
        listener.onReextractRequested(new MatchingIntentReextractRequestedEvent(USER_ID));

        verify(matchingIntentService).reextractCompleted(USER_ID);
    }

    @Test
    @DisplayName("재추출이 터져도 예외를 밖으로 내보내지 않는다")
    void swallowsFailure() {
        doThrow(new RuntimeException("AI 다운")).when(matchingIntentService).reextractCompleted(USER_ID);

        assertThatCode(() -> listener.onReextractRequested(
          new MatchingIntentReextractRequestedEvent(USER_ID)))
          .doesNotThrowAnyException();
    }
}

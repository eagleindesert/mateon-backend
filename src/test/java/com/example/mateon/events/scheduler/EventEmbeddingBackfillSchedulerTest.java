package com.example.mateon.events.scheduler;

import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.events.service.EventEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 백필 한 틱의 껍데기. 후보 선정은 쿼리가 하고, 여기서 지킬 것은 빈 목록에서 AI 를 안
 * 부르는지, 나온 id 를 빠짐없이 넘기는지, 한 건이 터져도 다음을 계속 하는지다.
 */
class EventEmbeddingBackfillSchedulerTest {

    private EventRepository eventRepository;
    private EventEmbeddingService eventEmbeddingService;
    private EventEmbeddingBackfillScheduler scheduler;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        eventEmbeddingService = mock(EventEmbeddingService.class);
        scheduler = new EventEmbeddingBackfillScheduler(eventRepository, eventEmbeddingService);
        ReflectionTestUtils.setField(scheduler, "batchSize", 10);
        ReflectionTestUtils.setField(scheduler, "maxFailures", 8);
        ReflectionTestUtils.setField(scheduler, "retryCooldown", Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("후보가 없으면 refresh 를 부르지 않는다")
    void skipsRefreshWhenEmpty() {
        when(eventRepository.findIdsNeedingEmbedding(anyInt(), anyInt(), any()))
          .thenReturn(List.of());

        scheduler.backfill();

        verifyNoInteractions(eventEmbeddingService);
    }

    @Test
    @DisplayName("나온 id 마다 refresh 를 부른다")
    void refreshesEachId() {
        when(eventRepository.findIdsNeedingEmbedding(anyInt(), anyInt(), any()))
          .thenReturn(List.of(11L, 12L));

        scheduler.backfill();

        verify(eventEmbeddingService).refresh(11L);
        verify(eventEmbeddingService).refresh(12L);
    }

    @Test
    @DisplayName("한 건이 예외여도 다음 id 를 계속 부른다")
    void continuesAfterOneFailure() {
        when(eventRepository.findIdsNeedingEmbedding(anyInt(), anyInt(), any()))
          .thenReturn(List.of(1L, 2L, 3L));
        doThrow(new RuntimeException("AI 다운")).when(eventEmbeddingService).refresh(1L);

        assertThatCode(() -> scheduler.backfill()).doesNotThrowAnyException();

        verify(eventEmbeddingService).refresh(1L);
        verify(eventEmbeddingService).refresh(2L);
        verify(eventEmbeddingService).refresh(3L);
    }

    @Test
    @DisplayName("쿨다운이 없으면 10분으로 본다")
    void defaultsCooldownWhenNull() {
        when(eventRepository.findIdsNeedingEmbedding(anyInt(), anyInt(), any()))
          .thenReturn(List.of());
        ReflectionTestUtils.setField(scheduler, "retryCooldown", null);

        LocalDateTime before = LocalDateTime.now().minusMinutes(10);
        scheduler.backfill();
        LocalDateTime after = LocalDateTime.now().minusMinutes(10);

        ArgumentCaptor<LocalDateTime> retryBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(eventRepository).findIdsNeedingEmbedding(eq(10), eq(8), retryBefore.capture());
        assertThat(retryBefore.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    @DisplayName("쿨다운이 음수면 10분으로 본다")
    void defaultsCooldownWhenNegative() {
        when(eventRepository.findIdsNeedingEmbedding(anyInt(), anyInt(), any()))
          .thenReturn(List.of());
        ReflectionTestUtils.setField(scheduler, "retryCooldown", Duration.ofMinutes(-1));

        LocalDateTime before = LocalDateTime.now().minusMinutes(10);
        scheduler.backfill();
        LocalDateTime after = LocalDateTime.now().minusMinutes(10);

        ArgumentCaptor<LocalDateTime> retryBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(eventRepository).findIdsNeedingEmbedding(eq(10), eq(8), retryBefore.capture());
        assertThat(retryBefore.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    @DisplayName("한도와 쿨다운 기준 시각을 쿼리에 실어 보낸다")
    void passesFailureCapAndRetryCutoff() {
        when(eventRepository.findIdsNeedingEmbedding(anyInt(), anyInt(), any()))
          .thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now().minusMinutes(10);
        scheduler.backfill();
        LocalDateTime after = LocalDateTime.now().minusMinutes(10);

        ArgumentCaptor<LocalDateTime> retryBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(eventRepository).findIdsNeedingEmbedding(eq(10), eq(8), retryBefore.capture());
        assertThat(retryBefore.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        verify(eventEmbeddingService, never()).refresh(any());
    }
}

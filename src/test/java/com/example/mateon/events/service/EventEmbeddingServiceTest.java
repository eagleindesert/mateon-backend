package com.example.mateon.events.service;

import com.example.mateon.common.ai.AiServerProperties;
import com.example.mateon.events.client.ContestEmbeddingClient;
import com.example.mateon.events.client.ContestEmbeddingRefreshRequest;
import com.example.mateon.events.client.ContestEmbeddingRefreshResponse;
import com.example.mateon.events.domain.EventEmbedding;
import com.example.mateon.events.domain.EventEmbeddingRefreshStatus;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventEmbeddingRepository;
import com.example.mateon.events.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 공모전 임베딩 갱신의 저장 규약을 고정한다. 경합 판정은 팀 임베딩과 같고,
 * 여기서는 공모전 쪽에만 있는 입력 조립(description 빈 문자열)과 차원 검증을 본다.
 */
class EventEmbeddingServiceTest {

    private static final long EVENT_ID = 337930L;
    private static final int DIMENSION = 4;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 30, 10, 0, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 8, 30, 10, 0, 1);

    private EventRepository eventRepository;
    private EventEmbeddingRepository eventEmbeddingRepository;
    private ContestEmbeddingClient client;
    private EventEmbeddingService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        eventEmbeddingRepository = mock(EventEmbeddingRepository.class);
        client = mock(ContestEmbeddingClient.class);

        AiServerProperties properties = mock(AiServerProperties.class);
        when(properties.getEmbeddingDimension()).thenReturn(DIMENSION);

        service = new EventEmbeddingService(eventRepository, eventEmbeddingRepository, client,
          properties);
    }

    @Test
    @DisplayName("활동이 없으면 AI 를 부르지 않는다")
    void skipsWhenEventMissing() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

        service.refresh(EVENT_ID);

        verifyNoInteractions(client);
        verifyNoInteractions(eventEmbeddingRepository);
    }

    @Test
    @DisplayName("description 이 null 이면 빈 문자열로 보낸다 (AI 가 required)")
    void sendsEmptyDescriptionWhenNull() {
        givenEvent(CREATED_AT, null);
        when(eventEmbeddingRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
        givenAiResponse(new double[]{0.1, 0.2, 0.3, 0.4});

        service.refresh(EVENT_ID);

        ArgumentCaptor<ContestEmbeddingRefreshRequest> captor =
          ArgumentCaptor.forClass(ContestEmbeddingRefreshRequest.class);
        verify(client).refresh(captor.capture());
        assertThat(captor.getValue().getDescription()).isEmpty();
        assertThat(captor.getValue().getTitle()).isEqualTo("테스트 공모전");
        assertThat(captor.getValue().getEventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("차원이 맞지 않으면 저장하지 않고 FAILED 를 남긴다")
    void skipsSaveOnDimensionMismatch() {
        givenEvent(CREATED_AT, "설명");
        when(eventEmbeddingRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
        givenAiResponse(new double[]{0.1, 0.2});

        service.refresh(EVENT_ID);

        EventEmbedding saved = captureSaved();
        assertThat(saved.getRefreshStatus()).isEqualTo(EventEmbeddingRefreshStatus.FAILED);
        assertThat(saved.getEmbedding()).isNull();
        assertThat(saved.getLastError()).contains("차원 불일치");
    }

    @Test
    @DisplayName("생성 시점 결과가 늦게 도착해도 수정 시점 결과를 덮지 않는다")
    void discardsStaleResult() {
        givenEvent(CREATED_AT, "설명");
        givenStoredRow(UPDATED_AT, vector(0.9, 0.8, 0.7, 0.6));
        givenAiResponse(new double[]{0.1, 0.2, 0.3, 0.4});

        service.refresh(EVENT_ID);

        verify(eventEmbeddingRepository, never()).save(any());
    }

    @Test
    @DisplayName("첫 갱신은 그대로 저장된다")
    void savesFirstRefresh() {
        givenEvent(CREATED_AT, "설명");
        when(eventEmbeddingRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
        givenAiResponse(new double[]{0.1, 0.2, 0.3, 0.4});

        service.refresh(EVENT_ID);

        EventEmbedding saved = captureSaved();
        assertThat(saved.getEmbedding()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(saved.getSourceUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(saved.getRefreshStatus()).isEqualTo(EventEmbeddingRefreshStatus.SUCCESS);
        assertThat(saved.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("실패는 행의 기준 시점을 올리지 않는다")
    void failureKeepsStoredTimestamp() {
        givenEvent(UPDATED_AT, "설명");
        givenStoredRow(CREATED_AT, vector(0.9, 0.8, 0.7, 0.6));
        when(client.refresh(any())).thenThrow(new RuntimeException("AI 서버 다운"));

        assertThatThrownBy(() -> service.refresh(EVENT_ID)).isInstanceOf(RuntimeException.class);

        EventEmbedding saved = captureSaved();
        assertThat(saved.getRefreshStatus()).isEqualTo(EventEmbeddingRefreshStatus.FAILED);
        assertThat(saved.getSourceUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(saved.getEmbedding()).containsExactly(0.9f, 0.8f, 0.7f, 0.6f);
    }

    @Test
    @DisplayName("응답에 event_id 가 없어도 우리가 아는 id 로 저장한다")
    void ignoresMissingEchoedEventId() {
        givenEvent(CREATED_AT, "설명");
        when(eventEmbeddingRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
        ContestEmbeddingRefreshResponse response = new ContestEmbeddingRefreshResponse();
        response.setEventId(null);
        response.setEmbeddingVector(new double[]{0.1, 0.2, 0.3, 0.4});
        when(client.refresh(any())).thenReturn(response);

        service.refresh(EVENT_ID);

        assertThat(captureSaved().getEventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("응답 event_id 가 달라도 우리가 아는 id 로 저장한다")
    void ignoresMismatchedEchoedEventId() {
        givenEvent(CREATED_AT, "설명");
        when(eventEmbeddingRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
        ContestEmbeddingRefreshResponse response = new ContestEmbeddingRefreshResponse();
        response.setEventId(EVENT_ID + 1);
        response.setEmbeddingVector(new double[]{0.1, 0.2, 0.3, 0.4});
        when(client.refresh(any())).thenReturn(response);

        service.refresh(EVENT_ID);

        assertThat(captureSaved().getEventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("벡터가 없으면 저장하지 않고 FAILED 를 남긴다")
    void skipsSaveWhenVectorIsNull() {
        givenEvent(CREATED_AT, "설명");
        when(eventEmbeddingRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
        givenAiResponse(null);

        service.refresh(EVENT_ID);

        EventEmbedding saved = captureSaved();
        assertThat(saved.getRefreshStatus()).isEqualTo(EventEmbeddingRefreshStatus.FAILED);
        assertThat(saved.getEmbedding()).isNull();
        assertThat(saved.getLastError()).contains("actual=null");
    }

    @Test
    @DisplayName("활동 수정 시각이 없으면 낡은 결과로 보지 않는다")
    void savesWhenSourceTimestampUnknown() {
        givenEvent(null, "설명");
        givenStoredRow(UPDATED_AT, vector(0.9, 0.8, 0.7, 0.6));
        givenAiResponse(new double[]{0.1, 0.2, 0.3, 0.4});

        service.refresh(EVENT_ID);

        EventEmbedding saved = captureSaved();
        assertThat(saved.getEmbedding()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(saved.getSourceUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("저장이 두 번 충돌하면 예외를 올리고 실패 기록은 삼킨다")
    void rethrowsAfterSaveConflictsAndSwallowsFailureRecording() {
        givenEvent(CREATED_AT, "설명");
        when(eventEmbeddingRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
        givenAiResponse(new double[]{0.1, 0.2, 0.3, 0.4});
        when(eventEmbeddingRepository.save(any()))
          .thenThrow(new OptimisticLockingFailureException("버전 충돌"));

        assertThatThrownBy(() -> service.refresh(EVENT_ID))
          .isInstanceOf(OptimisticLockingFailureException.class);

        verify(eventEmbeddingRepository, times(4)).save(any());
    }

    @Test
    @DisplayName("실패 사유는 500자로 자른다")
    void truncatesLongFailureReason() {
        givenEvent(CREATED_AT, "설명");
        when(eventEmbeddingRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
        when(client.refresh(any())).thenThrow(new RuntimeException("e".repeat(600)));

        assertThatThrownBy(() -> service.refresh(EVENT_ID)).isInstanceOf(RuntimeException.class);

        assertThat(captureSaved().getLastError()).hasSize(500);
    }

    @Test
    @DisplayName("실패 사유가 없으면 last_error 도 비운다")
    void truncateNullReasonReturnsNull() throws Exception {
        var method = EventEmbeddingService.class.getDeclaredMethod("truncate", String.class);
        method.setAccessible(true);

        assertThat(method.invoke(service, new Object[]{null})).isNull();
    }

    private void givenEvent(LocalDateTime updatedAt, String description) {
        Event event = new Event();
        event.setId(EVENT_ID);
        event.setTitle("테스트 공모전");
        event.setDescription(description);
        event.setUpdatedAt(updatedAt);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
    }

    private void givenStoredRow(LocalDateTime sourceUpdatedAt, float[] embedding) {
        EventEmbedding row = new EventEmbedding();
        row.setEventId(EVENT_ID);
        row.setEmbedding(embedding);
        row.setSourceUpdatedAt(sourceUpdatedAt);
        row.setRefreshStatus(EventEmbeddingRefreshStatus.SUCCESS);
        when(eventEmbeddingRepository.findById(EVENT_ID)).thenReturn(Optional.of(row));
    }

    private void givenAiResponse(double[] vector) {
        ContestEmbeddingRefreshResponse response = new ContestEmbeddingRefreshResponse();
        response.setEventId(EVENT_ID);
        response.setEmbeddingVector(vector);
        when(client.refresh(any())).thenReturn(response);
    }

    private EventEmbedding captureSaved() {
        ArgumentCaptor<EventEmbedding> captor = ArgumentCaptor.forClass(EventEmbedding.class);
        verify(eventEmbeddingRepository).save(captor.capture());
        return captor.getValue();
    }

    private static float[] vector(double... values) {
        float[] out = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (float) values[i];
        }
        return out;
    }
}

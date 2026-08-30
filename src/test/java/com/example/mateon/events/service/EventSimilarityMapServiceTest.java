package com.example.mateon.events.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.client.ContestSimilarityMapClient;
import com.example.mateon.events.client.ContestSimilarityMapRequest;
import com.example.mateon.events.client.ContestSimilarityMapResponse;
import com.example.mateon.events.client.ContestSimilarityMapResponse.Point;
import com.example.mateon.events.client.ContestSimilarityMapResponse.Query;
import com.example.mateon.events.domain.EventEmbedding;
import com.example.mateon.events.dto.ContestSimilarityMapResponseDTO;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.repository.EventEmbeddingRepository;
import com.example.mateon.events.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventSimilarityMapServiceTest {

    private static final long QUERY_ID = 10L;
    private static final long CANDIDATE_A = 11L;
    private static final long CANDIDATE_B = 12L;
    private static final long CANDIDATE_C = 13L;

    private EventRepository eventRepository;
    private EventEmbeddingRepository eventEmbeddingRepository;
    private ContestSimilarityMapClient client;
    private EventSimilarityMapService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        eventEmbeddingRepository = mock(EventEmbeddingRepository.class);
        client = mock(ContestSimilarityMapClient.class);
        service = new EventSimilarityMapService(eventRepository, eventEmbeddingRepository, client);
    }

    @Test
    @DisplayName("활동이 없으면 EVENT_NOT_FOUND")
    void missingEventIsNotFound() {
        when(eventRepository.findById(QUERY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.map(QUERY_ID, 500))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.EVENT_NOT_FOUND);
        verify(client, never()).map(any());
    }

    @Test
    @DisplayName("임베딩 행이 없으면 EVENT_EMBEDDING_NOT_READY")
    void missingEmbeddingIsNotReady() {
        when(eventRepository.findById(QUERY_ID)).thenReturn(Optional.of(event(QUERY_ID, "기준")));
        when(eventEmbeddingRepository.findById(QUERY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.map(QUERY_ID, 500))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.EVENT_EMBEDDING_NOT_READY);
        verify(client, never()).map(any());
    }

    @Test
    @DisplayName("임베딩 벡터가 null 이면 EVENT_EMBEDDING_NOT_READY")
    void nullVectorIsNotReady() {
        when(eventRepository.findById(QUERY_ID)).thenReturn(Optional.of(event(QUERY_ID, "기준")));
        when(eventEmbeddingRepository.findById(QUERY_ID))
          .thenReturn(Optional.of(embedding(QUERY_ID, null)));

        assertThatThrownBy(() -> service.map(QUERY_ID, 500))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.EVENT_EMBEDDING_NOT_READY);
    }

    @Test
    @DisplayName("후보가 없으면 AI 를 부르지 않고 빈 points 를 돌려준다")
    void emptyCandidatesSkipAi() {
        givenQueryReady();
        when(eventEmbeddingRepository.findByEmbeddingIsNotNullAndEventIdNot(QUERY_ID))
          .thenReturn(List.of());

        ContestSimilarityMapResponseDTO dto = service.map(QUERY_ID, 500);

        verify(client, never()).map(any());
        assertThat(dto.getPoints()).isEmpty();
        assertThat(dto.getReferenceRings()).isEmpty();
        assertThat(dto.getCandidatePoolTotal()).isZero();
        assertThat(dto.getQuery().getId()).isEqualTo(QUERY_ID);
        assertThat(dto.getQuery().getFieldLabel()).isEqualTo("교육");
        assertThat(dto.getMaxRadius()).isEqualTo(12.0);
        assertThat(dto.getMinRadius()).isEqualTo(2.6);
    }

    @Test
    @DisplayName("AI 가 보낸 적 없는 id 는 버린다")
    void dropsUnknownIds() {
        givenQueryReady();
        Event candidate = event(CANDIDATE_A, "후보A");
        when(eventEmbeddingRepository.findByEmbeddingIsNotNullAndEventIdNot(QUERY_ID))
          .thenReturn(List.of(embedding(CANDIDATE_A, new float[]{1f, 0f})));
        when(eventRepository.findAllById(List.of(CANDIDATE_A))).thenReturn(List.of(candidate));
        when(client.map(any())).thenReturn(aiResponse(
          point("999", 0.9),
          point(String.valueOf(CANDIDATE_A), 0.8)));

        ContestSimilarityMapResponseDTO dto = service.map(QUERY_ID, 500);

        assertThat(dto.getPoints()).extracting(ContestSimilarityMapResponseDTO.Point::getId)
          .containsExactly(CANDIDATE_A);
    }

    @Test
    @DisplayName("topN 을 넘으면 코사인 유사도 상위만 AI 로 보낸다")
    void truncatesByCosineWhenOverTopN() {
        givenQueryReady();
        Event near = event(CANDIDATE_A, "가까움");
        Event mid = event(CANDIDATE_B, "중간");
        Event far = event(CANDIDATE_C, "멂");
        when(eventEmbeddingRepository.findByEmbeddingIsNotNullAndEventIdNot(QUERY_ID))
          .thenReturn(List.of(
            embedding(CANDIDATE_A, new float[]{1f, 0f}),
            embedding(CANDIDATE_B, new float[]{0.5f, 0.5f}),
            embedding(CANDIDATE_C, new float[]{0f, 1f})));
        when(eventRepository.findAllById(any())).thenReturn(List.of(near, mid, far));
        when(client.map(any())).thenReturn(aiResponse(point(String.valueOf(CANDIDATE_A), 0.99)));

        service.map(QUERY_ID, 1);

        ArgumentCaptor<ContestSimilarityMapRequest> captor =
          ArgumentCaptor.forClass(ContestSimilarityMapRequest.class);
        verify(client).map(captor.capture());
        assertThat(captor.getValue().getCandidates()).hasSize(1);
        assertThat(captor.getValue().getCandidates().get(0).getId())
          .isEqualTo(String.valueOf(CANDIDATE_A));
        assertThat(captor.getValue().getTopN()).isEqualTo(1);
    }

    @Test
    @DisplayName("topN 1 미만은 1 로, 500 초과는 500 으로 자른다")
    void clampsTopN() {
        assertThat(EventSimilarityMapService.clampTopN(0)).isEqualTo(1);
        assertThat(EventSimilarityMapService.clampTopN(-3)).isEqualTo(1);
        assertThat(EventSimilarityMapService.clampTopN(501)).isEqualTo(500);
        assertThat(EventSimilarityMapService.clampTopN(500)).isEqualTo(500);
    }

    @Test
    @DisplayName("같은 방향 벡터의 코사인은 1 에 가깝다")
    void cosineOfAlignedVectors() {
        assertThat(EventSimilarityMapService.cosine(new float[]{1f, 0f}, new float[]{2f, 0f}))
          .isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    private void givenQueryReady() {
        when(eventRepository.findById(QUERY_ID)).thenReturn(Optional.of(event(QUERY_ID, "기준")));
        when(eventEmbeddingRepository.findById(QUERY_ID))
          .thenReturn(Optional.of(embedding(QUERY_ID, new float[]{1f, 0f})));
    }

    private static Event event(long id, String title) {
        Event event = new Event();
        event.setId(id);
        event.setTitle(title);
        event.setCategory(Category.CONTEST);
        event.setField(Field.EDUCATION);
        event.setOrganizer("주최");
        event.setDetailUrl("https://example.com/" + id);
        return event;
    }

    private static EventEmbedding embedding(long eventId, float[] vector) {
        EventEmbedding row = new EventEmbedding();
        row.setEventId(eventId);
        row.setEmbedding(vector);
        return row;
    }

    private static ContestSimilarityMapResponse aiResponse(Point... points) {
        ContestSimilarityMapResponse response = new ContestSimilarityMapResponse();
        Query query = new Query();
        query.setId(String.valueOf(QUERY_ID));
        query.setTitle("기준");
        response.setQuery(query);
        response.setPoints(List.of(points));
        response.setMaxRadius(12.0);
        response.setMinRadius(2.6);
        response.setRadialJitter(0.5);
        response.setReferenceRings(List.of());
        response.setCandidatePoolTotal(points.length);
        return response;
    }

    private static Point point(String id, double similarity) {
        Point point = new Point();
        point.setId(id);
        point.setSimilarity(similarity);
        point.setRankPercentile(0.0);
        point.setRadius(2.6);
        point.setX(1.0);
        point.setY(0.0);
        return point;
    }
}

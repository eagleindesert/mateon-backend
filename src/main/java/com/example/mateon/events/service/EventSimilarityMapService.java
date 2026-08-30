package com.example.mateon.events.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.client.ContestSimilarityMapClient;
import com.example.mateon.events.client.ContestSimilarityMapRequest;
import com.example.mateon.events.client.ContestSimilarityMapRequest.ContestItem;
import com.example.mateon.events.client.ContestSimilarityMapResponse;
import com.example.mateon.events.domain.EventEmbedding;
import com.example.mateon.events.dto.ContestSimilarityMapResponseDTO;
import com.example.mateon.events.dto.ContestSimilarityMapResponseDTO.Point;
import com.example.mateon.events.dto.ContestSimilarityMapResponseDTO.Query;
import com.example.mateon.events.dto.ContestSimilarityMapResponseDTO.ReferenceRing;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventEmbeddingRepository;
import com.example.mateon.events.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공모전 유사도 지도 오케스트레이터.
 *
 * <p>
 * 클래스 레벨 @Transactional 이 없다 — FastAPI read-timeout 동안 DB 커넥션을 잡고 있지 않기
 * 위해서다 (RecommendationService 와 같은 이유). 조회는 리포지토리의 짧은 트랜잭션으로 끝낸 뒤
 * TX 밖에서 AI 를 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventSimilarityMapService {

    /**
     * AI 기본값과 같은 상한. 후보 하나당 1536 차원 벡터를 실어 보내므로 이 값이 곧 페이로드
     * 크기다. 명세의 top_n 기본값과 맞춘다.
     */
    public static final int DEFAULT_TOP_N = 500;

    static final double MAX_RADIUS = 12.0;
    static final double MIN_RADIUS = 2.6;
    static final double RADIAL_JITTER = 0.5;

    private final EventRepository eventRepository;
    private final EventEmbeddingRepository eventEmbeddingRepository;
    private final ContestSimilarityMapClient client;

    public ContestSimilarityMapResponseDTO map(Long eventId, int topN) {
        int clampedTopN = clampTopN(topN);

        Event queryEvent = eventRepository.findById(eventId)
          .orElseThrow(() -> new MateonException(ErrorCode.EVENT_NOT_FOUND));
        EventEmbedding queryEmbedding = eventEmbeddingRepository.findById(eventId)
          .orElseThrow(() -> new MateonException(ErrorCode.EVENT_EMBEDDING_NOT_READY));
        if (queryEmbedding.getEmbedding() == null) {
            throw new MateonException(ErrorCode.EVENT_EMBEDDING_NOT_READY);
        }

        List<EventEmbedding> embeddingRows
          = eventEmbeddingRepository.findByEmbeddingIsNotNullAndEventIdNot(eventId);
        if (embeddingRows.isEmpty()) {
            return emptyMap(queryEvent);
        }

        Map<Long, Event> eventsById = eventRepository.findAllById(
          embeddingRows.stream().map(EventEmbedding::getEventId).toList())
          .stream()
          .collect(Collectors.toMap(Event::getId, Function.identity()));

        List<Candidate> candidates = new ArrayList<>();
        for (EventEmbedding row : embeddingRows) {
            Event event = eventsById.get(row.getEventId());
            if (event == null || row.getEmbedding() == null) {
                continue;
            }
            candidates.add(new Candidate(event, row.getEmbedding(),
              cosine(queryEmbedding.getEmbedding(), row.getEmbedding())));
        }

        if (candidates.size() > clampedTopN) {
            candidates.sort(Comparator.comparingDouble(Candidate::similarity).reversed());
            candidates = new ArrayList<>(candidates.subList(0, clampedTopN));
        }

        if (candidates.isEmpty()) {
            return emptyMap(queryEvent);
        }

        ContestSimilarityMapResponse ai = client.map(new ContestSimilarityMapRequest(
          toItem(queryEvent, queryEmbedding.getEmbedding()),
          candidates.stream().map(c -> toItem(c.event(), c.embedding())).toList(),
          clampedTopN));

        return toDto(queryEvent, eventsById, ai);
    }

    static int clampTopN(int topN) {
        return Math.min(Math.max(topN, 1), DEFAULT_TOP_N);
    }

    private ContestSimilarityMapResponseDTO emptyMap(Event queryEvent) {
        return new ContestSimilarityMapResponseDTO(
          toQuery(queryEvent),
          List.of(),
          MAX_RADIUS,
          MIN_RADIUS,
          RADIAL_JITTER,
          List.of(),
          0);
    }

    private ContestSimilarityMapResponseDTO toDto(Event queryEvent, Map<Long, Event> eventsById,
      ContestSimilarityMapResponse ai) {
        List<ContestSimilarityMapResponse.Point> aiPoints
          = ai.getPoints() == null ? List.of() : ai.getPoints();

        List<Point> points = new ArrayList<>();
        int dropped = 0;
        for (ContestSimilarityMapResponse.Point aiPoint : aiPoints) {
            Long id = parseId(aiPoint.getId());
            Event event = id == null ? null : eventsById.get(id);
            if (event == null || aiPoint.getSimilarity() == null
              || aiPoint.getRankPercentile() == null || aiPoint.getRadius() == null
              || aiPoint.getX() == null || aiPoint.getY() == null) {
                dropped++;
                continue;
            }
            points.add(new Point(
              event.getId(),
              event.getTitle(),
              event.getOrganizer(),
              categoryName(event),
              fieldName(event),
              fieldLabel(event, aiPoint.getFieldLabel()),
              event.getDetailUrl(),
              aiPoint.getSimilarity(),
              aiPoint.getRankPercentile(),
              aiPoint.getRadius(),
              aiPoint.getX(),
              aiPoint.getY()));
        }
        if (dropped > 0) {
            log.warn("유사도 지도 응답에서 {}건을 버렸습니다 (알 수 없는 id 또는 좌표 누락). queryEventId={}",
              dropped, queryEvent.getId());
        }

        List<ReferenceRing> rings = ai.getReferenceRings() == null
          ? List.of()
          : ai.getReferenceRings().stream()
            .filter(r -> r.getPercentile() != null
            && r.getSimilarityAtPercentile() != null
            && r.getRadius() != null)
            .map(r -> new ReferenceRing(
            r.getPercentile(), r.getSimilarityAtPercentile(), r.getRadius()))
            .toList();

        return new ContestSimilarityMapResponseDTO(
          toQuery(queryEvent),
          points,
          ai.getMaxRadius() != null ? ai.getMaxRadius() : MAX_RADIUS,
          ai.getMinRadius() != null ? ai.getMinRadius() : MIN_RADIUS,
          ai.getRadialJitter() != null ? ai.getRadialJitter() : RADIAL_JITTER,
          rings,
          ai.getCandidatePoolTotal() != null ? ai.getCandidatePoolTotal() : points.size());
    }

    private static Query toQuery(Event event) {
        return new Query(
          event.getId(),
          event.getTitle(),
          event.getOrganizer(),
          categoryName(event),
          fieldName(event),
          event.getField() != null ? event.getField().getLabel() : null,
          event.getDetailUrl());
    }

    private static ContestItem toItem(Event event, float[] embedding) {
        return new ContestItem(
          String.valueOf(event.getId()),
          embedding,
          event.getTitle(),
          event.getOrganizer(),
          categoryName(event),
          fieldName(event),
          event.getDetailUrl());
    }

    private static String categoryName(Event event) {
        return event.getCategory() != null ? event.getCategory().name() : null;
    }

    private static String fieldName(Event event) {
        return event.getField() != null ? event.getField().name() : null;
    }

    private static String fieldLabel(Event event, String aiLabel) {
        if (aiLabel != null && !aiLabel.isBlank()) {
            return aiLabel;
        }
        return event.getField() != null ? event.getField().getLabel() : null;
    }

    private static Long parseId(String id) {
        if (id == null) {
            return null;
        }
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return Double.NEGATIVE_INFINITY;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            double av = a[i];
            double bv = b[i];
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record Candidate(Event event, float[] embedding, double similarity) {

    }
}

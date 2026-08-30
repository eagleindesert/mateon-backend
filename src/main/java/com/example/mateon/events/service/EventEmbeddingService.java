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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * 공모전 임베딩 갱신 오케스트레이터. 활동 등록 커밋 후 비동기 리스너·백필 스케줄러에서 호출된다.
 *
 * <p>
 * 클래스에 @Transactional 을 걸지 않는 이유: AI 호출이 read-timeout 까지 걸릴 수 있어
 * 그동안 DB 커넥션을 점유하면 안 된다 (TeamEmbeddingService 와 같은 원칙). 조회와 upsert 는
 * 각각 리포지토리 자체의 짧은 트랜잭션으로 충분하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventEmbeddingService {

    /**
     * last_error 저장 상한. 진단 단서만 남기면 되므로 길게 둘 이유가 없다.
     */
    private static final int MAX_ERROR_LENGTH = 500;

    /**
     * 버전 충돌 시 재판정 포함 최대 저장 시도 횟수 (saveIfFresh 주석 참고).
     */
    private static final int MAX_SAVE_ATTEMPTS = 2;

    private final EventRepository eventRepository;
    private final EventEmbeddingRepository eventEmbeddingRepository;
    private final ContestEmbeddingClient client;
    private final AiServerProperties properties;

    /**
     * 활동 정보를 fresh 조회해 AI 서버로 임베딩을 계산하고 event_embeddings 에 upsert 한다.
     */
    public void refresh(Long eventId) {
        Event event = eventRepository.findById(eventId).orElse(null);
        if (event == null) {
            log.warn("공모전 임베딩 갱신 스킵: 활동이 존재하지 않음 (eventId={})", eventId);
            return;
        }

        LocalDateTime sourceUpdatedAt = event.getUpdatedAt();

        try {
            String description = event.getDescription() == null ? "" : event.getDescription();
            ContestEmbeddingRefreshRequest request = new ContestEmbeddingRefreshRequest(
              eventId, event.getTitle(), description);

            ContestEmbeddingRefreshResponse ai = client.refresh(request);

            if (ai.getEventId() != null && !ai.getEventId().equals(eventId)) {
                log.warn("공모전 임베딩 응답 event_id 불일치: sent={}, echoed={}",
                  eventId, ai.getEventId());
            }

            upsert(eventId, ai, sourceUpdatedAt);
        } catch (Exception e) {
            recordFailure(eventId, sourceUpdatedAt, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * 차원 검증이 필수인 이유: vector(1536) 컬럼에 다른 길이를 넣으면 DB 예외로 원인 불명이
     * 된다. 비동기 경로라 예외 대신 warn 후 중단한다 — 기존 임베딩(있다면)이 유지된다.
     */
    private void upsert(Long eventId, ContestEmbeddingRefreshResponse ai,
      LocalDateTime sourceUpdatedAt) {
        double[] vector = ai.getEmbeddingVector();
        if (vector == null || vector.length != properties.getEmbeddingDimension()) {
            log.warn("공모전 임베딩 차원 불일치로 저장 스킵: eventId={}, expected={}, actual={}",
              eventId, properties.getEmbeddingDimension(),
              vector == null ? null : vector.length);
            recordFailure(eventId, sourceUpdatedAt, "차원 불일치: expected=" + properties.getEmbeddingDimension()
              + ", actual=" + (vector == null ? null : vector.length));
            return;
        }

        float[] embedding = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            embedding[i] = (float) vector[i];
        }

        boolean saved = saveIfFresh(eventId, sourceUpdatedAt, entity -> {
            entity.setEmbedding(embedding);
            entity.setRefreshStatus(EventEmbeddingRefreshStatus.SUCCESS);
            entity.setLastAttemptedAt(LocalDateTime.now());
            entity.setConsecutiveFailures(0);
            entity.setLastError(null);
            entity.setSourceUpdatedAt(sourceUpdatedAt);
        });

        if (saved) {
            log.info("공모전 임베딩 저장 완료: eventId={}", eventId);
        }
    }

    /**
     * 갱신 실패를 행에 남긴다. 행이 없으면 벡터 없이(embedding=null) 만든다.
     *
     * <p>
     * 기존 임베딩은 건드리지 않는다. 낡은 값이라도 남겨 두는 편이 유사도 지도에서 통째로
     * 사라지는 것보다 낫다. 같은 이유로 source_updated_at 도 올리지 않는다.
     */
    private void recordFailure(Long eventId, LocalDateTime sourceUpdatedAt, String reason) {
        try {
            saveIfFresh(eventId, sourceUpdatedAt, entity -> {
                entity.setRefreshStatus(EventEmbeddingRefreshStatus.FAILED);
                entity.setLastAttemptedAt(LocalDateTime.now());
                entity.setConsecutiveFailures(entity.getConsecutiveFailures() + 1);
                entity.setLastError(truncate(reason));
            });
        } catch (Exception e) {
            log.warn("공모전 임베딩 갱신 실패 상태 기록 실패 (원래 실패 원인은 호출부 로그 참고). eventId={}",
              eventId, e);
        }
    }

    /**
     * 낡은 결과를 걸러 내고 저장한다.
     *
     * <p>
     * 막으려는 것: 등록 갱신과 백필이 동시에 돌 때, AI 응답이 늦게 온 쪽이 무조건 이기는
     * last-write-wins. 도착한 결과가 이미 저장된 것보다 낡았으면 버린다.
     *
     * @return 저장했으면 true, 낡은 결과라 버렸으면 false
     */
    private boolean saveIfFresh(Long eventId, LocalDateTime sourceUpdatedAt,
      Consumer<EventEmbedding> mutator) {
        for (int attempt = 1;; attempt++) {
            EventEmbedding entity = loadOrCreate(eventId);

            LocalDateTime stored = entity.getSourceUpdatedAt();
            if (stored != null && sourceUpdatedAt != null && sourceUpdatedAt.isBefore(stored)) {
                log.info("낡은 공모전 임베딩 결과 폐기: eventId={}, 결과 기준={}, 행 기준={}",
                  eventId, sourceUpdatedAt, stored);
                return false;
            }

            mutator.accept(entity);

            try {
                eventEmbeddingRepository.save(entity);
                return true;
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
                if (attempt >= MAX_SAVE_ATTEMPTS) {
                    throw e;
                }
                log.debug("공모전 임베딩 저장 충돌 — 다시 읽어 재판정: eventId={}", eventId);
            }
        }
    }

    private EventEmbedding loadOrCreate(Long eventId) {
        return eventEmbeddingRepository.findById(eventId)
          .orElseGet(() -> {
              EventEmbedding created = new EventEmbedding();
              created.setEventId(eventId);
              return created;
          });
    }

    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_ERROR_LENGTH ? reason : reason.substring(0, MAX_ERROR_LENGTH);
    }
}

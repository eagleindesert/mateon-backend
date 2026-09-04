package com.example.mateon.events.scheduler;

import com.example.mateon.common.ai.AiServerProperties;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.events.service.EventEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 임베딩이 없는 활동을 조금씩 채워 넣는다.
 *
 * <p>
 * 등록 경로의 @Async 는 앱이 재시작되면 큐가 사라지고, 이미 DB 에 있는 공모전은 이벤트가
 * 한 번도 안 나간다. 유사도 지도가 빈 그래프가 되지 않게 구멍을 메운다.
 *
 * <p>
 * 벡터가 있는 활동은 후보가 아니다. 한 번도 성공하지 못한 행만 다시 집어 채우고, 연속 실패가
 * 한도를 넘기면 그 행은 끝낸다. 빈 틱에서 스케줄을 끄지 않는다 — 재시작 후 큐 유실을
 * 회수해야 하기 때문이다.
 *
 * <p>
 * 한 틱에 처리하는 건수를 묶는 이유: FastAPI 호출이 활동 수만큼 나가기 때문이다.
 * 크롤러 적재와 겹치면 {@code EventEmbeddingService} 의 source_updated_at 판정이 낡은
 * 결과를 버린다.
 *
 * <p>
 * 단일 인스턴스 전제다 ({@code SchedulingConfig} 주석과 같다). 멱등이라 데이터가 망가지지는
 * 않지만, 스케일아웃 시 같은 활동을 여러 대가 동시에 때릴 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.event-embedding-backfill-enabled", havingValue = "true",
  matchIfMissing = true)
public class EventEmbeddingBackfillScheduler {

    private final EventRepository eventRepository;
    private final EventEmbeddingService eventEmbeddingService;

    /**
     * 배치 크기·실패 한도·재시도 쿨다운. 필드 @Value 대신 생성자로 받는 이유는 테스트가
     * 필드명 문자열로 값을 밀어 넣지 않게 하기 위해서다 — 그 방식은 이름을 바꾸면 컴파일은
     * 통과하고 런타임에만 깨진다.
     */
    private final AiServerProperties properties;

    @Scheduled(fixedDelayString = "${ai.event-embedding-backfill-delay:60s}")
    public void backfill() {
        int limit = Math.max(properties.getEventEmbeddingBackfillBatchSize(), 1);
        int failuresCap = Math.max(properties.getEventEmbeddingBackfillMaxFailures(), 1);
        Duration retryCooldown = properties.getEventEmbeddingBackfillRetryCooldown();
        Duration cooldown = retryCooldown == null || retryCooldown.isNegative()
          ? Duration.ofMinutes(10)
          : retryCooldown;
        LocalDateTime retryBefore = LocalDateTime.now().minus(cooldown);
        List<Long> ids = eventRepository.findIdsNeedingEmbedding(limit, failuresCap, retryBefore);
        if (ids.isEmpty()) {
            return;
        }

        int failed = 0;
        for (Long eventId : ids) {
            try {
                eventEmbeddingService.refresh(eventId);
            } catch (Exception e) {
                failed++;
                log.warn("공모전 임베딩 백필 실패: eventId={}", eventId, e);
            }
        }
        log.info("공모전 임베딩 백필: 대상={}건, 실패={}건", ids.size(), failed);
    }
}

package com.example.mateon.events.scheduler;

import com.example.mateon.events.domain.EventEmbedding;
import com.example.mateon.events.domain.EventEmbeddingRefreshStatus;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.repository.EventEmbeddingRepository;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백필 후보 쿼리를 실제 Postgres 에 대고 고정한다.
 *
 * <p>
 * 벡터가 있으면 후보가 아니라는 조건은 목으로 지킬 수 없다. native query 가 컬럼을
 * 어떻게 읽는지만 보면 된다. AI 는 부르지 않는다.
 */
class EventEmbeddingBackfillQueryIntegrationTest extends IntegrationTestBase {

    private static final int MAX_FAILURES = 8;
    private static final LocalDateTime RETRY_BEFORE = LocalDateTime.of(2026, 9, 2, 12, 0, 0);

    @Autowired
    EventRepository eventRepository;
    @Autowired
    EventEmbeddingRepository eventEmbeddingRepository;
    @Autowired
    EntityManager entityManager;

    @Test
    @DisplayName("행이 없는 활동은 후보다")
    void includesEventWithoutRow() {
        Long missing = newEvent();

        assertThat(candidates(10)).containsExactly(missing);
    }

    @Test
    @DisplayName("SUCCESS 벡터가 있으면 후보가 아니다")
    void excludesSuccessfulEmbedding() {
        Long done = newEvent();
        saveRow(done, vector(), EventEmbeddingRefreshStatus.SUCCESS, 0, RETRY_BEFORE.minusHours(1));
        Long missing = newEvent();

        assertThat(candidates(10)).containsExactly(missing);
    }

    @Test
    @DisplayName("FAILED 여도 낡은 벡터가 있으면 후보가 아니다")
    void excludesFailedRowThatStillHasVector() {
        Long stale = newEvent();
        saveRow(stale, vector(), EventEmbeddingRefreshStatus.FAILED, 1, RETRY_BEFORE.minusHours(1));
        Long missing = newEvent();

        assertThat(candidates(10)).containsExactly(missing);
    }

    @Test
    @DisplayName("한 번도 성공 못 했고 한도·쿨다운을 통과하면 후보다")
    void includesFailedNullEmbeddingWithinLimits() {
        Long retryable = newEvent();
        saveRow(retryable, null, EventEmbeddingRefreshStatus.FAILED, 3, RETRY_BEFORE.minusMinutes(1));

        assertThat(candidates(10)).containsExactly(retryable);
    }

    @Test
    @DisplayName("연속 실패가 한도 이상이면 후보에서 뺀다")
    void excludesWhenFailuresReachCap() {
        Long givenUp = newEvent();
        saveRow(givenUp, null, EventEmbeddingRefreshStatus.FAILED, MAX_FAILURES,
          RETRY_BEFORE.minusHours(1));
        Long missing = newEvent();

        assertThat(candidates(10)).containsExactly(missing);
    }

    @Test
    @DisplayName("마지막 시도가 retryBefore 이후면 이번 틱에서 뺀다")
    void excludesWhenLastAttemptIsTooRecent() {
        Long cooling = newEvent();
        saveRow(cooling, null, EventEmbeddingRefreshStatus.FAILED, 1, RETRY_BEFORE);
        Long missing = newEvent();

        assertThat(candidates(10)).containsExactly(missing);
    }

    @Test
    @DisplayName("앞 id 가 포기·쿨다운이면 뒤 id 가 LIMIT 안으로 들어온다")
    void skippedHeadDoesNotBlockLaterIds() {
        Long givenUp = newEvent();
        saveRow(givenUp, null, EventEmbeddingRefreshStatus.FAILED, MAX_FAILURES,
          RETRY_BEFORE.minusHours(1));
        Long cooling = newEvent();
        saveRow(cooling, null, EventEmbeddingRefreshStatus.FAILED, 1, RETRY_BEFORE.plusMinutes(1));
        Long missing = newEvent();

        assertThat(candidates(1)).containsExactly(missing);
    }

    private List<Long> candidates(int limit) {
        entityManager.flush();
        entityManager.clear();
        return eventRepository.findIdsNeedingEmbedding(limit, MAX_FAILURES, RETRY_BEFORE);
    }

    private Long newEvent() {
        Event event = new Event();
        event.setCategory(Category.CONTEST);
        event.setField(Field.EDUCATION);
        event.setTitle("백필 후보 테스트");
        return eventRepository.saveAndFlush(event).getId();
    }

    private void saveRow(Long eventId, float[] embedding, EventEmbeddingRefreshStatus status,
      int consecutiveFailures, LocalDateTime lastAttemptedAt) {
        EventEmbedding row = new EventEmbedding();
        row.setEventId(eventId);
        row.setEmbedding(embedding);
        row.setRefreshStatus(status);
        row.setConsecutiveFailures(consecutiveFailures);
        row.setLastAttemptedAt(lastAttemptedAt);
        eventEmbeddingRepository.saveAndFlush(row);
    }

    private static float[] vector() {
        return new float[1536];
    }
}

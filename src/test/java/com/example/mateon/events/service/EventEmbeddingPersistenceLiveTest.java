package com.example.mateon.events.service;

import com.example.mateon.events.client.ContestEmbeddingClient;
import com.example.mateon.events.domain.EventEmbedding;
import com.example.mateon.events.domain.EventEmbeddingRefreshStatus;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.repository.EventEmbeddingRepository;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.support.AiStubSupport;
import com.example.mateon.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 가 준 공모전 임베딩이 pgvector 컬럼까지 온전히 가는지 확인한다.
 *
 * <p>
 * 운영 경로인 {@code EventEmbeddingRefreshListener} 는 {@code @Async} 라 여기서 쓰지 않는다.
 */
class EventEmbeddingPersistenceLiveTest extends IntegrationTestBase {

    @Autowired
    EventRepository eventRepository;
    @Autowired
    EventEmbeddingRepository eventEmbeddingRepository;
    @Autowired
    EntityManager entityManager;

    private EventEmbeddingService service;

    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        service = new EventEmbeddingService(
          eventRepository,
          eventEmbeddingRepository,
          new ContestEmbeddingClient(AiStubSupport.aiCallTemplate()),
          AiStubSupport.properties());
    }

    @Test
    @DisplayName("AI 임베딩이 vector(1536) 컬럼에 저장되고 그대로 되읽힌다")
    void embeddingSurvivesRoundTrip() {
        Long eventId = newEvent();

        service.refresh(eventId);

        EventEmbedding saved = reload(eventId);
        assertThat(saved.getEmbedding()).hasSize(1536);
        assertThat(saved.getRefreshStatus()).isEqualTo(EventEmbeddingRefreshStatus.SUCCESS);
    }

    @Test
    @DisplayName("저장된 벡터 값이 전부 유한하다 (double→float 변환이 값을 깨지 않는다)")
    void storedVectorValuesAreFinite() {
        Long eventId = newEvent();

        service.refresh(eventId);

        for (float value : reload(eventId).getEmbedding()) {
            assertThat(Float.isFinite(value))
              .as("저장된 벡터에 유한하지 않은 값이 있다: %s", value)
              .isTrue();
        }
    }

    private EventEmbedding reload(Long eventId) {
        entityManager.flush();
        entityManager.clear();
        return eventEmbeddingRepository.findById(eventId).orElseThrow();
    }

    private Long newEvent() {
        Event event = new Event();
        event.setCategory(Category.CONTEST);
        event.setField(Field.EDUCATION);
        event.setTitle("임베딩테스트 공모전");
        event.setDescription("유사도 지도용 임베딩을 계산한다.");
        return eventRepository.save(event).getId();
    }
}

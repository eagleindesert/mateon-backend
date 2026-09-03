package com.example.mateon.events.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공모전 임베딩 행의 persist/update 콜백이 시각을 채우는지 고정한다.
 *
 * <p>
 * Hibernate 가 부르는 메서드라 단위 테스트에서 {@code new} 만 하면 비어 있다. 유저
 * 임베딩과 같은 이유로 직접 호출한다.
 */
class EventEmbeddingTest {

    @Test
    @DisplayName("persist/update 콜백에서 시각을 채운다")
    void lifecycleStampsTimestamps() {
        EventEmbedding embedding = new EventEmbedding();
        embedding.setEventId(1L);
        embedding.setEmbedding(new float[]{0.1f, 0.2f});

        embedding.onCreate();
        LocalDateTime created = embedding.getCreatedAt();
        assertThat(created).isNotNull();
        assertThat(embedding.getUpdatedAt()).isEqualTo(created);

        embedding.onUpdate();
        assertThat(embedding.getUpdatedAt()).isAfterOrEqualTo(created);
    }
}

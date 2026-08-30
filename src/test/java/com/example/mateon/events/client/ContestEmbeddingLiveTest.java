package com.example.mateon.events.client;

import com.example.mateon.support.AiStubSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공모전 임베딩 갱신 응답이 ai-stub 에서 실제로 돌아와 DTO 로 채워지는지 확인한다.
 *
 * <p>
 * 저장까지 이어지는 구간은 {@code EventEmbeddingPersistenceLiveTest} 가 본다.
 * 여기서는 DTO 까지만 본다 — 두 층을 한 클래스에 합치면 실패했을 때
 * "역직렬화가 깨진 건지 저장이 깨진 건지"를 구분하지 못한다.
 */
class ContestEmbeddingLiveTest {

    private ContestEmbeddingClient client;

    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        client = new ContestEmbeddingClient(AiStubSupport.aiCallTemplate());
    }

    @Test
    @DisplayName("1536 차원 임베딩과 event_id echo 가 채워진다")
    void embeddingIsMapped() {
        ContestEmbeddingRefreshResponse response = client.refresh(request());

        assertThat(response.getEmbeddingVector()).hasSize(1536);
        assertThat(response.getEventId()).isEqualTo(337930L);
    }

    @Test
    @DisplayName("임베딩 값이 전부 유한하다 (NaN/Infinity 가 섞여 있지 않다)")
    void embeddingValuesAreFinite() {
        ContestEmbeddingRefreshResponse response = client.refresh(request());

        for (double value : response.getEmbeddingVector()) {
            assertThat(Double.isFinite(value))
              .as("임베딩에 유한하지 않은 값이 있다: %s", value)
              .isTrue();
        }
    }

    private ContestEmbeddingRefreshRequest request() {
        return new ContestEmbeddingRefreshRequest(
          337930L,
          "경기도 1인가구 정책제안 아이디어 공모전",
          "경기도 1인가구의 삶의 질 향상을 위한 정책 아이디어를 공모합니다.");
    }
}

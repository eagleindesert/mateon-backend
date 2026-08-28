package com.example.mateon.matching.client.intent;

import com.example.mateon.support.AiStubSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 의도 추출 응답이 ai-stub 에서 실제로 돌아와 DTO 로 채워지는지 확인한다.
 *
 * <p>
 * 이 클라이언트의 응답 파싱은 {@link IntentExtractionClientTest} 가 이미 실제 HTTP 경로로
 * 보고 있다(거기는 {@code MockRestServiceServer} 로 응답 JSON 을 직접 적어 준다).
 * 그래서 여기서 새로 얻는 건 <b>스텁이 실제로 보내는 모양</b>에 대고 확인한다는 점 하나다.
 *
 * <p>
 * 확인 대상은 재질문/완료 두 분기다. 스텁은 {@code messages} 개수로 갈라 1개면 임베딩을
 * 주지 않고 2개 이상이면 준다. 이 분기가 성립해야 {@code missing_fields} 로 흐름을 판정하는
 * {@link IntentExtractResponse#isCompleted()} 가 의미를 갖는다.
 */
class IntentExtractionLiveTest {

    private IntentExtractionClient client;

    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        client = new IntentExtractionClient(
          AiStubSupport.aiRestTemplate(), AiStubSupport.properties());
    }

    @Test
    @DisplayName("발화 1개 — 재질문 분기: missing_fields 가 남고 임베딩은 오지 않는다")
    void singleMessageAsksAgain() {
        IntentExtractResponse response = client.extract(List.of("디자인 팀 찾고 있어요"));

        assertThat(response.getMissingFields()).isNotEmpty();
        assertThat(response.isCompleted()).isFalse();
        assertThat(response.getAssistantMessage()).isNotBlank();
        assertThat(response.getEmbeddingVector()).isNull();
    }

    @Test
    @DisplayName("발화 2개 — 완료 분기: 1536 차원 임베딩과 extracted 가 함께 온다")
    void twoMessagesComplete() {
        IntentExtractResponse response = client.extract(
          List.of("디자인 팀 찾고 있어요", "완전 처음이에요"));

        assertThat(response.isCompleted()).isTrue();
        assertThat(response.getEmbeddingVector()).hasSize(1536);
        assertThat(response.getEmbeddingText()).isNotBlank();
    }

    /**
     * 중첩 객체의 매핑은 바깥과 따로 깨진다 — {@code Extracted} 안의 {@code @JsonProperty}
     * 가 어긋나도 바깥 필드는 멀쩡해서 응답이 정상으로 보인다. 그래서 따로 본다.
     */
    @Test
    @DisplayName("중첩 extracted 의 스네이크 케이스 필드가 채워진다")
    void nestedExtractedIsMapped() {
        IntentExtractResponse response = client.extract(
          List.of("디자인 팀 찾고 있어요", "완전 처음이에요"));

        IntentExtractResponse.Extracted extracted = response.getExtracted();

        assertThat(extracted).isNotNull();
        assertThat(extracted.getDesiredRoles()).isNotNull();
        assertThat(extracted.getExperienceLevel()).isNotNull();
    }
}

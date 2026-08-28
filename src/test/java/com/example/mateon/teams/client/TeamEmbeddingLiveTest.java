package com.example.mateon.teams.client;

import com.example.mateon.support.AiStubSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 팀 임베딩 갱신 응답이 ai-stub 에서 실제로 돌아와 DTO 로 채워지는지 확인한다.
 *
 * <p>
 * 이 응답에는 이 프로젝트에서 가장 매핑이 깨지기 쉬운 조합이 다 들어 있다 — 최상위
 * 스네이크 케이스 4개, 중첩 {@code metadata} 6개, 그리고 {@code double[]} 배열.
 * 그런데 지금까지 이 DTO 로 실제 JSON 이 들어온 적이 없다.
 *
 * <p>
 * 저장까지 이어지는 구간은
 * {@code com.example.mateon.teams.service.TeamEmbeddingPersistenceLiveTest} 가 본다.
 * 여기서는 DTO 까지만 본다 — 두 층을 한 클래스에 합치면 실패했을 때
 * "역직렬화가 깨진 건지 저장이 깨진 건지"를 구분하지 못한다.
 */
class TeamEmbeddingLiveTest {

    private TeamEmbeddingClient client;

    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        client = new TeamEmbeddingClient(
          AiStubSupport.aiRestTemplate(), AiStubSupport.properties());
    }

    @Test
    @DisplayName("1536 차원 임베딩과 embedding_text 가 채워진다")
    void embeddingIsMapped() {
        TeamEmbeddingRefreshResponse response = client.refresh(request());

        assertThat(response.getEmbeddingVector()).hasSize(1536);
        assertThat(response.getEmbeddingText()).isNotBlank();
    }

    /**
     * 임베딩은 나중에 {@code float[]} 로 좁혀져 pgvector 컬럼에 들어간다. NaN/Infinity 가
     * 섞여 있으면 그 변환이나 저장 시점에야 터지는데, 그때는 원인이 AI 응답이라는 게
     * 드러나지 않는다. 들어온 자리에서 본다.
     */
    @Test
    @DisplayName("임베딩 값이 전부 유한하다 (NaN/Infinity 가 섞여 있지 않다)")
    void embeddingValuesAreFinite() {
        TeamEmbeddingRefreshResponse response = client.refresh(request());

        for (double value : response.getEmbeddingVector()) {
            assertThat(Double.isFinite(value))
              .as("임베딩에 유한하지 않은 값이 있다: %s", value)
              .isTrue();
        }
    }

    @Test
    @DisplayName("중첩 metadata 의 스네이크 케이스 필드가 채워진다")
    void nestedMetadataIsMapped() {
        TeamEmbeddingRefreshResponse.Metadata metadata = client.refresh(request()).getMetadata();

        assertThat(metadata).isNotNull();
        assertThat(metadata.getRecruitingRoles()).isNotNull();
        assertThat(metadata.getRequiredSkills()).isNotNull();
    }

    /**
     * 의도 추출과 달리 {@code missing_fields} 가 남아 있어도 벡터는 함께 온다는 게 명세다
     * (DTO 주석 참고). 스텁도 그 특성을 재현해 항상 한 건을 남긴다. 이걸 "미완료"로 읽어
     * 벡터를 버리는 변경이 들어오면 임베딩이 통째로 사라진다.
     */
    @Test
    @DisplayName("missing_fields 가 남아 있어도 벡터는 함께 온다")
    void missingFieldsDoNotSuppressVector() {
        TeamEmbeddingRefreshResponse response = client.refresh(request());

        assertThat(response.getMissingFields()).isNotEmpty();
        assertThat(response.getEmbeddingVector()).isNotNull();
    }

    // --- 픽스처 -------------------------------------------------------------
    // TeamEmbeddingRefreshRequestSerializationTest 와 같은 값이다.

    private TeamEmbeddingRefreshRequest request() {
        return new TeamEmbeddingRefreshRequest(
          "제목: 임베딩테스트 팀\n소개: 디자인 협업을 함께할 팀원을 찾습니다.",
          List.of("디자이너", "기획자"),
          List.of("Figma"),
          "DESIGN_PHOTO_ART_VIDEO");
    }
}

package com.example.mateon.events.client;

import com.example.mateon.common.ai.AiCallTemplate;
import com.example.mateon.common.ai.AiServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 공모전 임베딩 갱신 요청이 실제 와이어에 어떤 바이트로 나가는지 고정한다.
 *
 * <p>
 * 운영과 같은 RestTemplate 기본 컨버터를 태우고 본문 전체를 STRICT 로 비교하는 이유는
 * {@link com.example.mateon.matching.client.recommendation.RecommendationRequestSerializationTest}
 * 클래스 주석과 같다.
 */
class ContestEmbeddingRefreshRequestSerializationTest {

    private static final String BASE_URL = "http://ai.test:8001";
    private static final String REFRESH_URL = BASE_URL + "/internal/contests/embedding:refresh";

    private MockRestServiceServer server;
    private ContestEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setInternalSecret("test-secret");

        client = new ContestEmbeddingClient(new AiCallTemplate(restTemplate, properties));
    }

    @Test
    @DisplayName("본문 전체가 명세와 같다 (event_id 는 echo 용으로 보낸다)")
    void bodyMatchesSpec() {
        server.expect(requestTo(REFRESH_URL))
          .andExpect(method(org.springframework.http.HttpMethod.POST))
          .andExpect(content().json("""
            {
              "event_id": 337930,
              "title": "경기도 1인가구 정책제안 아이디어 공모전",
              "description": "경기도 1인가구의 삶의 질 향상을 위한 정책 아이디어를 공모합니다."
            }
            """, JsonCompareMode.STRICT))
          .andRespond(withSuccess("""
            {"event_id": 337930, "embedding_vector": [0.1, 0.2]}
            """, MediaType.APPLICATION_JSON));

        client.refresh(new ContestEmbeddingRefreshRequest(
          337930L,
          "경기도 1인가구 정책제안 아이디어 공모전",
          "경기도 1인가구의 삶의 질 향상을 위한 정책 아이디어를 공모합니다."));

        server.verify();
    }

    @Test
    @DisplayName("description 이 빈 문자열이어도 키는 나간다 (AI 가 required 라 키를 빼면 422)")
    void emptyDescriptionKeepsKey() {
        server.expect(requestTo(REFRESH_URL))
          .andExpect(content().json("""
            {
              "event_id": 1,
              "title": "제목만 있는 공모전",
              "description": ""
            }
            """, JsonCompareMode.STRICT))
          .andRespond(withSuccess("""
            {"event_id": 1, "embedding_vector": [0.1]}
            """, MediaType.APPLICATION_JSON));

        client.refresh(new ContestEmbeddingRefreshRequest(1L, "제목만 있는 공모전", ""));

        server.verify();
    }
}

package com.example.mateon.teams.client;

import com.example.mateon.common.ai.AiServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 팀 임베딩 갱신 요청이 실제 와이어에 어떤 바이트로 나가는지 고정한다.
 *
 * <p>
 * 운영과 같은 RestTemplate 기본 컨버터를 태우는 이유, 본문 전체를 STRICT 로 비교하는 이유는
 * {@link com.example.mateon.matching.client.recommendation.RecommendationRequestSerializationTest}
 * 클래스 주석에 적어 두었다.
 *
 * <p>
 * 여기서 {@code X-Internal-Secret} 헤더를 함께 확인하는 이유가 있다. 다른 AI 클라이언트들은
 * {@code AiCallTemplate} 을 거쳐서 그 헤더 규약을
 * {@link com.example.mateon.common.ai.AiCallTemplateTest} 가 한 벌로 보장하는데,
 * <b>이 클라이언트는 aiRestTemplate 을 직접 쓴다</b>. 즉 공통 테스트의 보장 밖에 있고, 헤더가
 * 빠지면 AI 가 401 로 거절하는데 우리 로그에는 502 로만 남아 "AI 서버가 이상하다"로 오진하게
 * 된다. (같은 사정인 곳이 하나 더 있다 —
 * {@link com.example.mateon.matching.client.intent.IntentExtractionClient})
 */
class TeamEmbeddingRefreshRequestSerializationTest {

    private static final String BASE_URL = "http://ai.test:8001";
    private static final String REFRESH_URL = BASE_URL + "/internal/teams/embedding:refresh";
    private static final String SECRET = "test-internal-secret";

    private static final String EMBEDDING_JSON = """
      {
        "missing_fields": [],
        "embedding_text": "제목: 임베딩테스트 팀",
        "embedding_vector": [0.1, 0.2],
        "metadata": {}
      }
      """;

    private MockRestServiceServer server;
    private TeamEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setInternalSecret(SECRET);

        client = new TeamEmbeddingClient(restTemplate, properties);
    }

    @Test
    @DisplayName("본문 전체가 명세와 같다 (team_id 는 보내지 않는다 — AI 는 저장하지 않는다)")
    void bodyMatchesSpec() {
        server.expect(requestTo(REFRESH_URL))
          .andExpect(method(org.springframework.http.HttpMethod.POST))
          .andExpect(content().json("""
            {
              "intro_text": "제목: 임베딩테스트 팀\\n소개: 디자인 협업을 함께할 팀원을 찾습니다.",
              "recruiting_roles": ["디자이너", "기획자"],
              "required_skills": ["Figma"],
              "contest_field": "DESIGN_PHOTO_ART_VIDEO"
            }
            """, JsonCompareMode.STRICT))
          .andRespond(withSuccess(EMBEDDING_JSON, MediaType.APPLICATION_JSON));

        client.refresh(request("DESIGN_PHOTO_ART_VIDEO"));

        server.verify();
    }

    @Test
    @DisplayName("자율 프로젝트는 contest_field 가 null 이다 — 키가 사라지지 않는다")
    void nullContestFieldKeepsKey() {
        server.expect(requestTo(REFRESH_URL))
          .andExpect(content().json("""
            {
              "intro_text": "제목: 임베딩테스트 팀\\n소개: 디자인 협업을 함께할 팀원을 찾습니다.",
              "recruiting_roles": ["디자이너", "기획자"],
              "required_skills": ["Figma"],
              "contest_field": null
            }
            """, JsonCompareMode.STRICT))
          .andRespond(withSuccess(EMBEDDING_JSON, MediaType.APPLICATION_JSON));

        client.refresh(request(null));

        server.verify();
    }

    @Test
    @DisplayName("X-Internal-Secret 헤더가 붙는다 (AiCallTemplate 을 거치지 않으므로 여기서만 잡힌다)")
    void sendsInternalSecretHeader() {
        server.expect(requestTo(REFRESH_URL))
          .andExpect(header("X-Internal-Secret", SECRET))
          .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith(
            MediaType.APPLICATION_JSON_VALUE)))
          .andRespond(withSuccess(EMBEDDING_JSON, MediaType.APPLICATION_JSON));

        client.refresh(request("DESIGN_PHOTO_ART_VIDEO"));

        server.verify();
    }

    // --- 픽스처 -------------------------------------------------------------

    private TeamEmbeddingRefreshRequest request(String contestField) {
        return new TeamEmbeddingRefreshRequest(
          "제목: 임베딩테스트 팀\n소개: 디자인 협업을 함께할 팀원을 찾습니다.",
          List.of("디자이너", "기획자"),
          List.of("Figma"),
          contestField);
    }
}

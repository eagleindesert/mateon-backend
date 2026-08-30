package com.example.mateon.events.client;

import com.example.mateon.common.ai.AiCallTemplate;
import com.example.mateon.common.ai.AiServerProperties;
import com.example.mateon.events.client.ContestSimilarityMapRequest.ContestItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 유사도 지도 요청이 실제 와이어에 어떤 바이트로 나가는지 고정한다.
 *
 * <p>
 * 후보 배열을 반드시 채워 둔다. 비워 두면 embedding_vector 와 중첩 필드의 직렬화가
 * 한 번도 돌지 않아 검증이 있는 척만 하게 된다.
 */
class ContestSimilarityMapRequestSerializationTest {

    private static final String BASE_URL = "http://ai.test:8001";
    private static final String MAP_URL = BASE_URL + "/contests/similarity-map";

    private static final String EMPTY_MAP_JSON = """
      {
        "query": {"id": "1", "title": "기준"},
        "points": [],
        "max_radius": 12.0,
        "min_radius": 2.6,
        "radial_jitter": 0.5,
        "reference_rings": [],
        "candidate_pool_total": 0
      }
      """;

    private MockRestServiceServer server;
    private ContestSimilarityMapClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setInternalSecret("test-secret");

        client = new ContestSimilarityMapClient(new AiCallTemplate(restTemplate, properties));
    }

    @Test
    @DisplayName("본문 전체가 명세와 같다 (id 는 우리 events.id 문자열, 벡터는 float[])")
    void bodyMatchesSpec() {
        server.expect(requestTo(MAP_URL))
          .andExpect(content().json("""
            {
              "query": {
                "id": "325453",
                "embedding_vector": [0.5, -0.25],
                "title": "제1회 대학생 국제기구 입찰 경진대회",
                "organizer": "(사)정부조달수출진흥협회",
                "category": "CONTEST",
                "field": "EDUCATION",
                "detail_url": "https://linkareer.com/activity/325453"
              },
              "candidates": [
                {
                  "id": "328417",
                  "embedding_vector": [0.25, 0.75],
                  "title": "한솔그룹 AI 숏폼 공모전",
                  "organizer": "한솔그룹",
                  "category": "CONTEST",
                  "field": "PLANNING_IDEA",
                  "detail_url": "https://linkareer.com/activity/328417"
                }
              ],
              "top_n": 500
            }
            """, JsonCompareMode.STRICT))
          .andRespond(withSuccess(EMPTY_MAP_JSON, MediaType.APPLICATION_JSON));

        client.map(new ContestSimilarityMapRequest(
          new ContestItem("325453", new float[]{0.5f, -0.25f},
            "제1회 대학생 국제기구 입찰 경진대회",
            "(사)정부조달수출진흥협회", "CONTEST", "EDUCATION",
            "https://linkareer.com/activity/325453"),
          List.of(new ContestItem("328417", new float[]{0.25f, 0.75f},
            "한솔그룹 AI 숏폼 공모전", "한솔그룹", "CONTEST", "PLANNING_IDEA",
            "https://linkareer.com/activity/328417")),
          500));

        server.verify();
    }

    @Test
    @DisplayName("optional 필드가 null 이어도 키는 남는다")
    void nullOptionalFieldsKeepKeys() {
        server.expect(requestTo(MAP_URL))
          .andExpect(content().json("""
            {
              "query": {
                "id": "1",
                "embedding_vector": [0.1],
                "title": "기준",
                "organizer": null,
                "category": null,
                "field": null,
                "detail_url": null
              },
              "candidates": [
                {
                  "id": "2",
                  "embedding_vector": [0.2],
                  "title": "후보",
                  "organizer": null,
                  "category": null,
                  "field": null,
                  "detail_url": null
                }
              ],
              "top_n": 1
            }
            """, JsonCompareMode.STRICT))
          .andRespond(withSuccess(EMPTY_MAP_JSON, MediaType.APPLICATION_JSON));

        client.map(new ContestSimilarityMapRequest(
          new ContestItem("1", new float[]{0.1f}, "기준", null, null, null, null),
          List.of(new ContestItem("2", new float[]{0.2f}, "후보", null, null, null, null)),
          1));

        server.verify();
    }
}

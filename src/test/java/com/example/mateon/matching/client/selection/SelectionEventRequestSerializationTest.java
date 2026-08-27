package com.example.mateon.matching.client.selection;

import com.example.mateon.common.ai.AiCallTemplate;
import com.example.mateon.common.ai.AiServerProperties;
import com.example.mateon.matching.domain.SelectionDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 선택 이벤트 요청이 실제 와이어에 어떤 바이트로 나가는지 고정한다.
 *
 * <p>
 * 운영과 같은 RestTemplate 기본 컨버터를 태우는 이유, 본문 전체를 STRICT 로 비교하는 이유는
 * {@link com.example.mateon.matching.client.recommendation.RecommendationRequestSerializationTest}
 * 클래스 주석에 적어 두었다.
 *
 * <p>
 * 여기서 가장 값비싼 건 {@code component_scores} 다. 명세는 추천 당시 받은 원문 JSON 을 "값을
 * 재계산하거나 이름을 바꾸지 않고" 되돌려 주기를 요구하는데, 이 필드만 타입이 Map 이 아니라
 * String + {@code @JsonRawValue} 라서 애노테이션 하나가 빠지면 <b>객체가 문자열로 감싸여</b>
 * 나간다. 그래도 HTTP 200 이 떨어지므로 우리 쪽에는 아무 신호가 없고, AI 쪽 분석 품질로만
 * 드러난다.
 */
class SelectionEventRequestSerializationTest {

    private static final String BASE_URL = "http://ai.test:8001";
    private static final String SELECTION_EVENTS_URL = BASE_URL + "/selection-events";

    private static final String ACCEPTED_JSON = """
      {"accepted": true}
      """;

    private MockRestServiceServer server;
    private SelectionEventClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setInternalSecret("test-secret");

        client = new SelectionEventClient(new AiCallTemplate(restTemplate, properties));
    }

    @Test
    @DisplayName("본문 전체가 명세와 같다 (direction 은 enum 상수명, 노출 목록은 통째로 실린다)")
    void bodyMatchesSpec() {
        expectBody("""
          {
            "direction": "USER_TO_TEAM",
            "selected_candidate_id": 17,
            "selection_context": {
              "idempotency_key": "application:42",
              "chooser_fields": {
                "experience_level": "입문",
                "desired_roles": ["디자이너", "기획자"]
              },
              "shown_candidates": [
                {
                  "candidate_id": 17,
                  "total_score": 0.92,
                  "component_scores": {"similarity": 0.8, "role_match": 1.0}
                },
                {
                  "candidate_id": 18,
                  "total_score": 0.44,
                  "component_scores": null
                }
              ]
            }
          }
          """);

        client.send(request());

        server.verify();
    }

    /**
     * {@code @JsonRawValue} 가 빠지면 {@code "component_scores":"{\"similarity\":0.8}"} 처럼
     * 객체가 통째로 문자열이 된다. STRICT 비교가 타입까지 보므로 위 테스트로도 잡히지만,
     * 깨졌을 때 원인을 바로 알 수 있게 실패 메시지를 따로 남긴다.
     *
     * <p>
     * LENIENT 는 <b>키가 더 있는 것</b>만 봐주고 배열 길이는 그대로 맞춰야 해서, 노출 목록
     * 두 건을 다 적는다.
     */
    @Test
    @DisplayName("component_scores 는 객체다 — 문자열로 감싸이지 않는다")
    void componentScoresStayRawJson() {
        server.expect(requestTo(SELECTION_EVENTS_URL))
          .andExpect(content().json("""
            {
              "selection_context": {
                "shown_candidates": [
                  {"component_scores": {"similarity": 0.8, "role_match": 1.0}},
                  {"component_scores": null}
                ]
              }
            }
            """, JsonCompareMode.LENIENT))
          .andRespond(withSuccess(ACCEPTED_JSON, MediaType.APPLICATION_JSON));

        client.send(request());

        server.verify();
    }

    @Test
    @DisplayName("노출 목록이 비어도 키는 남는다 (빈 배열과 키 누락은 다른 뜻이다)")
    void emptyShownCandidatesKeepsKey() {
        expectBody("""
          {
            "direction": "TEAM_TO_USER",
            "selected_candidate_id": 7,
            "selection_context": {
              "idempotency_key": "offer:3",
              "chooser_fields": {},
              "shown_candidates": []
            }
          }
          """);

        client.send(new SelectionEventRequest(SelectionDirection.TEAM_TO_USER, 7L,
          new SelectionEventRequest.SelectionContext("offer:3", Map.of(), List.of())));

        server.verify();
    }

    // --- 하네스 -------------------------------------------------------------

    private void expectBody(String expectedJson) {
        server.expect(requestTo(SELECTION_EVENTS_URL))
          .andExpect(content().json(expectedJson, JsonCompareMode.STRICT))
          .andRespond(withSuccess(ACCEPTED_JSON, MediaType.APPLICATION_JSON));
    }

    // --- 픽스처 -------------------------------------------------------------

    private SelectionEventRequest request() {
        return new SelectionEventRequest(SelectionDirection.USER_TO_TEAM, 17L,
          new SelectionEventRequest.SelectionContext(
            "application:42",
            Map.of("experience_level", "입문", "desired_roles", List.of("디자이너", "기획자")),
            List.of(
              new SelectionEventRequest.ShownCandidate(17L, 0.92,
                "{\"similarity\":0.8,\"role_match\":1.0}"),
              new SelectionEventRequest.ShownCandidate(18L, 0.44, null))));
    }
}

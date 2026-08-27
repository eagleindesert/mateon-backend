package com.example.mateon.matching.client.proposal;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 제안 조립 요청이 실제 와이어에 어떤 바이트로 나가는지 고정한다.
 *
 * <p>
 * 운영과 같은 RestTemplate 기본 컨버터를 태우는 이유, 본문 전체를 STRICT 로 비교하는 이유는
 * {@link com.example.mateon.matching.client.recommendation.RecommendationRequestSerializationTest}
 * 클래스 주석에 적어 두었다.
 *
 * <p>
 * 아홉 개 키 중 앞의 여섯(user_id ~ intent_id)은 AI 가 계산에 쓰지 않고 응답에 되돌려 주기만
 * 한다. 그래서 이름이 틀리거나 빠져도 <b>문구는 멀쩡하게 생성된다</b> — 어긋난 사실은 나중에
 * AI 서버 로그로 어느 쌍의 요청인지 추적하려 할 때야 드러나고, 그때는 이미 지난 요청이라
 * 되돌릴 수 없다.
 *
 * <p>
 * 두 방향이 같은 스키마를 쓰고 경로만 다르다. 경로 자체의 분기는
 * {@link ProposalClientTest} 가 본다.
 */
class ProposalRequestSerializationTest {

    private static final String BASE_URL = "http://ai.test:8001";

    private static final String DRAFT_JSON = """
      {"summary": "요약", "message": "본문", "synergy_score": 0.87}
      """;

    private MockRestServiceServer server;
    private ProposalClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setInternalSecret("test-secret");

        client = new ProposalClient(new AiCallTemplate(restTemplate, properties));
    }

    @Test
    @DisplayName("유저→팀 본문 전체가 명세와 같다 (아홉 개 키)")
    void userToTeamBodyMatchesSpec() {
        expectBody("/proposals/user-to-team", """
          {
            "user_id": 7,
            "team_id": 42,
            "contest_id": 100,
            "sender_id": 7,
            "receiver_id": 42,
            "intent_id": 55,
            "synergy_score": 0.87,
            "candidate_summary": "커머스 플랫폼, BE 1명 결핍",
            "target_summary": "React/TypeScript 경험, 초보자"
          }
          """);

        client.userToTeam(request(100L));

        server.verify();
    }

    @Test
    @DisplayName("팀→유저도 같은 스키마다 (요청에 direction 이 없다 — 경로가 곧 방향이다)")
    void teamToUserUsesSameSchema() {
        expectBody("/proposals/team-to-user", """
          {
            "user_id": 7,
            "team_id": 42,
            "contest_id": 100,
            "sender_id": 7,
            "receiver_id": 42,
            "intent_id": 55,
            "synergy_score": 0.87,
            "candidate_summary": "커머스 플랫폼, BE 1명 결핍",
            "target_summary": "React/TypeScript 경험, 초보자"
          }
          """);

        client.teamToUser(request(100L));

        server.verify();
    }

    /**
     * 자율 프로젝트 팀은 teams.event_id 가 없어 contest_id 가 null 이 정상이다. 키가 통째로
     * 사라지면 FastAPI 가 필수 필드 누락으로 422 를 낼 수 있어, null 로 남는 쪽을 못박는다.
     */
    @Test
    @DisplayName("자율 프로젝트는 contest_id 가 null 이다 — 키가 사라지지 않는다")
    void nullContestIdKeepsKey() {
        expectBody("/proposals/user-to-team", """
          {
            "user_id": 7,
            "team_id": 42,
            "contest_id": null,
            "sender_id": 7,
            "receiver_id": 42,
            "intent_id": 55,
            "synergy_score": 0.87,
            "candidate_summary": "커머스 플랫폼, BE 1명 결핍",
            "target_summary": "React/TypeScript 경험, 초보자"
          }
          """);

        client.userToTeam(request(null));

        server.verify();
    }

    // --- 하네스 -------------------------------------------------------------

    private void expectBody(String path, String expectedJson) {
        server.expect(requestTo(BASE_URL + path))
          .andExpect(content().json(expectedJson, JsonCompareMode.STRICT))
          .andRespond(withSuccess(DRAFT_JSON, MediaType.APPLICATION_JSON));
    }

    // --- 픽스처 -------------------------------------------------------------

    private ProposalAssemblyRequest request(Long contestId) {
        return new ProposalAssemblyRequest(7L, 42L, contestId, 7L, 42L, 55L, 0.87,
          "커머스 플랫폼, BE 1명 결핍", "React/TypeScript 경험, 초보자");
    }
}

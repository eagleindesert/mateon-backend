package com.example.mateon.matching.client.recommendation;

import com.example.mateon.common.ai.AiCallTemplate;
import com.example.mateon.common.ai.AiServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * 추천 요청이 실제 와이어에 어떤 바이트로 나가는지 고정한다.
 *
 * <p>
 * 별도 ObjectMapper 로 직렬화해 보지 않고 <b>운영과 같은 RestTemplate 기본 컨버터</b>를 태운다.
 * 이 프로젝트는 Jackson 2 와 3 이 함께 클래스패스에 있고 스프링 부트 4 에는 양쪽 컨버터 설정이
 * 모두 들어 있어, "운영에서 누가 직렬화하는가"가 버전마다 답이 바뀌는 질문이다. 실제 요청을
 * 태우면 그 질문 자체가 사라진다 — 누가 이겼든 이긴 쪽이 만든 바이트를 보게 된다.
 *
 * <p>
 * 비교는 {@link JsonCompareMode#STRICT} 로 <b>본문 전체</b>를 본다. 키 하나를 콕 집어 확인하는
 * 방식으로는 <b>예상 밖의 키가 늘어난 경우</b>를 못 잡는다. 실제로 {@code @JsonProperty} 를
 * 게터가 아니라 필드에 달면 롬복 게터가 만든 이름과 병합되지 않아 키가 두 개 나가는데
 * (팀 상세 응답의 {@code isEnded} 가 그 사례다), 존재 확인만 하면 그대로 통과한다.
 *
 * <p>
 * 후보 배열을 반드시 채워 둔다. 비워 두면 {@code candidate_id}/{@code embedding_vector}/중첩
 * {@code metadata} 의 직렬화가 <b>한 번도 돌지 않아</b> 검증이 있는 척만 하게 된다.
 *
 * <p>
 * 메타데이터 값을 실제 값으로 적어 두는 것도 의도다. 이 DTO 들은 {@code @AllArgsConstructor}
 * 라 생성자 인자가 자리로만 구분되는데, 같은 타입(문자열/리스트)이 연달아 있어서
 * <b>필드를 하나 끼워 넣으면 그 뒤가 통째로 한 칸씩 밀린다</b>. 컴파일은 통과하고 키 이름도
 * 그대로라 값을 보지 않으면 못 잡는다.
 *
 * <p>
 * 경로별로 나눠 둔 이유는 {@link RecommendationClientTest} 와 같다 — user-to-team 과
 * team-to-user 는 요청 스키마가 거의 같아서, 뒤바뀌어 나가도 AI 가 그럴듯한 점수를 돌려준다.
 * 즉 500 이 나지 않고 추천 품질이 이상하다는 제보로만 드러난다.
 */
class RecommendationRequestSerializationTest {

    private static final String BASE_URL = "http://ai.test:8001";

    private MockRestServiceServer server;
    private RecommendationClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setInternalSecret("test-secret");

        client = new RecommendationClient(new AiCallTemplate(restTemplate, properties));
    }

    @Nested
    @DisplayName("POST /recommendations/user-to-team")
    class UserToTeam {

        @Test
        @DisplayName("본문 전체가 명세와 같다 (질의=유저 메타, 후보=팀 메타)")
        void bodyMatchesSpec() {
            expectBody("/recommendations/user-to-team", """
              {
                "query_embedding_vector": [0.5, -0.25],
                "query_metadata": {
                  "desired_roles": ["디자이너", "기획자"],
                  "skills": ["Figma"],
                  "experience_level": "입문",
                  "activity_style": "온라인",
                  "activity_time": null
                },
                "candidates": [
                  {
                    "candidate_id": 11,
                    "embedding_vector": [0.25, 0.75],
                    "metadata": {
                      "recruiting_roles": ["디자이너"],
                      "required_skills": ["Figma", "Notion"],
                      "activity_style": "온라인",
                      "beginner_friendly": true,
                      "activity_time": null,
                      "contest_field": null
                    }
                  },
                  {
                    "candidate_id": 12,
                    "embedding_vector": [-0.5, 0.125],
                    "metadata": {
                      "recruiting_roles": ["기획자"],
                      "required_skills": [],
                      "activity_style": "오프라인",
                      "beginner_friendly": null,
                      "activity_time": null,
                      "contest_field": null
                    }
                  }
                ]
              }
              """);

            client.userToTeam(userToTeamRequest());

            server.verify();
        }
    }

    @Nested
    @DisplayName("POST /recommendations/team-to-user")
    class TeamToUser {

        @Test
        @DisplayName("본문 전체가 명세와 같다 (질의=팀 메타, 후보=유저 메타 — 자리가 뒤집힌다)")
        void bodyMatchesSpec() {
            expectBody("/recommendations/team-to-user", """
              {
                "query_embedding_vector": [0.5, -0.25],
                "query_metadata": {
                  "recruiting_roles": ["디자이너"],
                  "required_skills": ["Figma", "Notion"],
                  "activity_style": "온라인",
                  "beginner_friendly": true,
                  "activity_time": null,
                  "contest_field": "DESIGN_PHOTO_ART_VIDEO"
                },
                "candidates": [
                  {
                    "candidate_id": 21,
                    "embedding_vector": [0.25, 0.75],
                    "metadata": {
                      "desired_roles": ["디자이너", "기획자"],
                      "skills": ["Figma"],
                      "experience_level": "입문",
                      "activity_style": "온라인",
                      "activity_time": null
                    }
                  },
                  {
                    "candidate_id": 22,
                    "embedding_vector": [-0.5, 0.125],
                    "metadata": {
                      "desired_roles": [],
                      "skills": [],
                      "experience_level": null,
                      "activity_style": null,
                      "activity_time": null
                    }
                  }
                ]
              }
              """);

            client.teamToUser(teamToUserRequest());

            server.verify();
        }

        @Test
        @DisplayName("contest_field 는 enum 상수명 그대로다 (한글 라벨이 아니다)")
        void contestFieldIsConstantName() {
            server.expect(requestTo(BASE_URL + "/recommendations/team-to-user"))
              .andExpect(content().json("""
                {"query_metadata": {"contest_field": "DESIGN_PHOTO_ART_VIDEO"}}
                """, JsonCompareMode.LENIENT))
              .andRespond(withSuccess(RECOMMENDATIONS_JSON, MediaType.APPLICATION_JSON));

            client.teamToUser(teamToUserRequest());

            server.verify();
        }
    }

    @Nested
    @DisplayName("POST /recommendations/reason")
    class Reason {

        @Test
        @DisplayName("본문 전체가 세 개의 요약 문자열이다 (벡터도 direction 도 보내지 않는다)")
        void bodyMatchesSpec() {
            server.expect(requestTo(BASE_URL + "/recommendations/reason"))
              .andExpect(content().json("""
                {
                  "candidate_summary": "후보 요약",
                  "target_summary": "대상 요약",
                  "score_context": "유사도 0.9, 역할 일치"
                }
                """, JsonCompareMode.STRICT))
              .andRespond(withSuccess("""
                {"reason": "스킬이 잘 맞습니다."}
                """, MediaType.APPLICATION_JSON));

            client.reason(new RecommendationReasonRequest("후보 요약", "대상 요약", "유사도 0.9, 역할 일치"));

            server.verify();
        }

        @Test
        @DisplayName("빈 score_context 도 그대로 나간다 (명세상 허용된다)")
        void emptyScoreContextIsSentAsIs() {
            server.expect(requestTo(BASE_URL + "/recommendations/reason"))
              .andExpect(content().json("""
                {
                  "candidate_summary": "후보 요약",
                  "target_summary": "대상 요약",
                  "score_context": ""
                }
                """, JsonCompareMode.STRICT))
              .andRespond(withSuccess("""
                {"reason": "스킬이 잘 맞습니다."}
                """, MediaType.APPLICATION_JSON));

            client.reason(new RecommendationReasonRequest("후보 요약", "대상 요약", ""));

            server.verify();
        }
    }

    @Nested
    @DisplayName("벡터 표기")
    class VectorRendering {

        /**
         * float 를 쓰는 근거가 요청 DTO 주석에 적혀 있다 — double 로 넓히면 0.1 이
         * 0.009999999776 처럼 늘어져 페이로드만 커진다는 것이다. 그 전제가 실제로 성립하는지
         * 여기서 확인한다. 깨져도 AI 는 숫자를 그대로 받아 계산하므로 아무 신호가 없고,
         * 벡터가 1536 차원까지 늘어나면 페이로드 크기로만 드러난다.
         */
        @Test
        @DisplayName("float 는 왕복 가능한 최단 표기로 나간다 (double 로 넓혀 늘어지지 않는다)")
        void floatUsesShortestRoundTripForm() {
            server.expect(requestTo(BASE_URL + "/recommendations/user-to-team"))
              .andExpect(content().json("""
                {"query_embedding_vector": [0.1, 0.2, 0.3]}
                """, JsonCompareMode.LENIENT))
              .andRespond(withSuccess(RECOMMENDATIONS_JSON, MediaType.APPLICATION_JSON));

            client.userToTeam(new UserToTeamRecommendationRequest(
              new float[]{0.1f, 0.2f, 0.3f},
              new UserMetadata(List.of(), List.of(), null, null, null),
              List.of()));

            server.verify();
        }
    }

    // --- 하네스 -------------------------------------------------------------

    private static final String RECOMMENDATIONS_JSON = """
      {"recommendations": []}
      """;

    private void expectBody(String path, String expectedJson) {
        server.expect(requestTo(BASE_URL + path))
          .andExpect(content().json(expectedJson, JsonCompareMode.STRICT))
          .andRespond(withSuccess(RECOMMENDATIONS_JSON, MediaType.APPLICATION_JSON));
    }

    // --- 픽스처 -------------------------------------------------------------

    private UserToTeamRecommendationRequest userToTeamRequest() {
        return new UserToTeamRecommendationRequest(
          new float[]{0.5f, -0.25f},
          new UserMetadata(List.of("디자이너", "기획자"), List.of("Figma"), "입문", "온라인", null),
          List.of(
            new UserToTeamRecommendationRequest.Candidate(11L, new float[]{0.25f, 0.75f},
              new TeamMetadata(List.of("디자이너"), List.of("Figma", "Notion"), "온라인", true,
                null, null)),
            new UserToTeamRecommendationRequest.Candidate(12L, new float[]{-0.5f, 0.125f},
              new TeamMetadata(List.of("기획자"), List.of(), "오프라인", null, null, null))));
    }

    private TeamToUserRecommendationRequest teamToUserRequest() {
        return new TeamToUserRecommendationRequest(
          new float[]{0.5f, -0.25f},
          new TeamMetadata(List.of("디자이너"), List.of("Figma", "Notion"), "온라인", true,
            null, "DESIGN_PHOTO_ART_VIDEO"),
          List.of(
            new TeamToUserRecommendationRequest.Candidate(21L, new float[]{0.25f, 0.75f},
              new UserMetadata(List.of("디자이너", "기획자"), List.of("Figma"), "입문", "온라인",
                null)),
            new TeamToUserRecommendationRequest.Candidate(22L, new float[]{-0.5f, 0.125f},
              new UserMetadata(List.of(), List.of(), null, null, null))));
    }
}

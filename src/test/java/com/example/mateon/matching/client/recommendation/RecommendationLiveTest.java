package com.example.mateon.matching.client.recommendation;

import com.example.mateon.support.AiStubSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 응답이 ai-stub 에서 실제로 돌아와 DTO 로 채워지는지 확인한다.
 *
 * <p>
 * {@link RecommendationClientTest} 는 {@code AiCallTemplate} 을 목으로 두고 응답 객체를
 * {@code ReflectionTestUtils.setField} 로 만든다. 즉 <b>JSON 이 한 번도 개입하지 않는다.</b>
 * 그래서 {@code @JsonProperty("candidate_id")} 를 지워도, 필드 이름을 바꿔도 그 테스트는
 * 초록이다. 어긋난 매핑은 예외가 아니라 <b>조용한 null</b> 로 나타난다 —
 * {@link RecommendationResponse} 가 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 로
 * 모르는 키를 삼키기 때문이다.
 *
 * <p>
 * 여기서 가장 중요한 건 {@code component_scores} 다. {@code Map} 이 아니라 {@code JsonNode}
 * 로 받는 유일한 필드인데, 지금까지 <b>한 번도 파싱된 적이 없다.</b> 이 값은 선택 피드백으로
 * 원문 그대로 되돌려 줘야 하는 것이라, 객체가 아니라 문자열로 들어오면 계약이 깨진다.
 *
 * <p>
 * 값이 아니라 <b>모양</b>만 단정한다. 스텁 payload 는 언제든 손볼 수 있어서 문구나 점수를
 * 단정하면 스텁을 고칠 때마다 깨지고, 그러면 이 테스트가 먼저 꺼진다.
 */
class RecommendationLiveTest {

    private RecommendationClient client;

    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        client = new RecommendationClient(AiStubSupport.aiCallTemplate());
    }

    @Test
    @DisplayName("user-to-team 응답이 DTO 로 채워진다 (candidate_id/score/label)")
    void userToTeamResponseIsMapped() {
        RecommendationResponse response = client.userToTeam(userToTeamRequest());

        assertThat(response.getRecommendations()).isNotEmpty();
        assertThat(response.getRecommendations()).allSatisfy(recommendation -> {
            assertThat(recommendation.getCandidateId()).isNotNull();
            assertThat(recommendation.getScore()).isNotNull();
            assertThat(recommendation.getLabel()).isNotBlank();
        });
    }

    @Test
    @DisplayName("우리가 보낸 candidate_id 가 그대로 돌아온다")
    void candidateIdsRoundTrip() {
        RecommendationResponse response = client.userToTeam(userToTeamRequest());

        assertThat(response.getRecommendations())
          .extracting(RecommendationResponse.Recommendation::getCandidateId)
          .containsExactlyInAnyOrder(11L, 12L);
    }

    /**
     * 이 테스트가 잡으려는 사고는 {@code JsonNode} 를 {@code String} 으로 바꾸는 변경이다.
     * 그렇게 하면 Jackson 이 객체를 문자열로 담지 못해 실패하거나, 담기더라도 이후
     * 선택 피드백에서 <b>따옴표로 감싼 문자열</b>이 나가 원문 보존 계약이 깨진다.
     */
    @Test
    @DisplayName("component_scores 는 객체 노드로 온다 (문자열이 아니다)")
    void componentScoresArriveAsObjectNode() {
        RecommendationResponse response = client.userToTeam(userToTeamRequest());

        assertThat(response.getRecommendations()).allSatisfy(recommendation -> {
            JsonNode scores = recommendation.getComponentScores();

            assertThat(scores).isNotNull();
            assertThat(scores.isObject())
              .as("component_scores 가 객체가 아니다: %s", scores)
              .isTrue();
            assertThat(scores.has("similarity")).isTrue();
            assertThat(scores.has("role_match")).isTrue();
        });
    }

    @Test
    @DisplayName("team-to-user 도 같은 스키마로 온다 (엔드포인트만 다르다)")
    void teamToUserResponseIsMapped() {
        RecommendationResponse response = client.teamToUser(teamToUserRequest());

        assertThat(response.getRecommendations()).isNotEmpty();
        assertThat(response.getRecommendations())
          .extracting(RecommendationResponse.Recommendation::getCandidateId)
          .containsExactlyInAnyOrder(21L, 22L);
    }

    @Test
    @DisplayName("reason 응답의 reason 문자열이 채워진다")
    void reasonIsMapped() {
        String reason = client.reason(
          new RecommendationReasonRequest("후보 요약", "대상 요약", "유사도 0.9, 역할 일치"));

        assertThat(reason).isNotBlank();
    }

    // --- 픽스처 -------------------------------------------------------------
    // RecommendationRequestSerializationTest 와 같은 값이다. 스텁이 역할 겹침으로 점수를
    // 가르므로(desired_roles ∩ recruiting_roles) 겹치는 조합을 그대로 쓴다.

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

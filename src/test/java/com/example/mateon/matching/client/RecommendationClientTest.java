package com.example.mateon.matching.client;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 추천 클라이언트가 <b>어느 경로로 보내고, 어떤 응답을 거부하는지</b>를 고정한다.
 * (인증 헤더·상태코드 매핑은 {@link AiCallTemplateTest} 가 이미 잡고 있으므로 여기서는 목을 쓴다.
 * {@code PortfolioSummaryClientTest} 와 같은 방식이다.)
 *
 * <p>경로를 못박는 이유: user-to-team 과 team-to-user 는 <b>요청 스키마가 거의 같아서</b>
 * 경로만 바꿔 보내도 AI 가 그럴듯한 점수를 돌려준다. 즉 뒤바뀌어도 500 이 나지 않고, 추천
 * 품질이 이상하다는 사용자 제보로만 드러난다.
 *
 * <p>거부 규칙도 마찬가지다 — {@code recommendations} 가 null 인 응답과 빈 reason 을 여기서
 * 막지 않으면, null 은 하류 NPE 로, 빈 이유는 <b>DB 캐시에 영구히</b> 남는다.
 */
class RecommendationClientTest {

    private AiCallTemplate ai;
    private RecommendationClient client;

    @BeforeEach
    void setUp() {
        ai = mock(AiCallTemplate.class);
        client = new RecommendationClient(ai);
    }

    @Nested
    @DisplayName("경로 — 두 방향이 섞이면 조용히 엉뚱한 추천이 나간다")
    class Paths {

        @Test
        @DisplayName("유저→팀은 /recommendations/user-to-team 으로 간다")
        void userToTeamPath() {
            when(ai.post(eq("/recommendations/user-to-team"), any(), eq(RecommendationResponse.class)))
                    .thenReturn(response(recommendation(10L, 0.9)));

            assertThat(client.userToTeam(userToTeamRequest()).getRecommendations()).hasSize(1);

            verify(ai).post(eq("/recommendations/user-to-team"), any(), eq(RecommendationResponse.class));
        }

        @Test
        @DisplayName("팀→유저는 /recommendations/team-to-user 로 간다")
        void teamToUserPath() {
            when(ai.post(eq("/recommendations/team-to-user"), any(), eq(RecommendationResponse.class)))
                    .thenReturn(response(recommendation(20L, 0.8)));

            assertThat(client.teamToUser(teamToUserRequest()).getRecommendations()).hasSize(1);

            verify(ai).post(eq("/recommendations/team-to-user"), any(), eq(RecommendationResponse.class));
        }

        @Test
        @DisplayName("상세 이유는 /recommendations/reason 으로 간다")
        void reasonPath() {
            when(ai.post(eq("/recommendations/reason"), any(), eq(RecommendationReasonResponse.class)))
                    .thenReturn(reasonResponse("스킬이 잘 맞습니다."));

            assertThat(client.reason(reasonRequest())).isEqualTo("스킬이 잘 맞습니다.");
        }
    }

    @Nested
    @DisplayName("응답 검증")
    class Validation {

        @Test
        @DisplayName("recommendations 가 null 이면 502 — null 을 흘려보내면 하류에서 NPE 가 난다")
        void nullRecommendationsIs502() {
            when(ai.post(any(), any(), eq(RecommendationResponse.class)))
                    .thenReturn(new RecommendationResponse());

            assertThatThrownBy(() -> client.userToTeam(userToTeamRequest()))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("빈 배열은 정상이다 — 후보는 있었는데 맞는 게 없었다는 뜻이다")
        void emptyRecommendationsIsFine() {
            when(ai.post(any(), any(), eq(RecommendationResponse.class))).thenReturn(response());

            assertThat(client.userToTeam(userToTeamRequest()).getRecommendations()).isEmpty();
        }

        @Test
        @DisplayName("빈 reason 은 502 — 빈 문자열이 캐시에 들어가면 영구히 남는다")
        void blankReasonIs502() {
            for (String blank : new String[]{null, "", "   "}) {
                when(ai.post(any(), any(), eq(RecommendationReasonResponse.class)))
                        .thenReturn(reasonResponse(blank));

                assertThatThrownBy(() -> client.reason(reasonRequest()))
                        .isInstanceOf(MateonException.class)
                        .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_ERROR);
            }
        }

        @Test
        @DisplayName("팀→유저도 같은 null 검증을 받는다 (한쪽만 고치는 사고 방지)")
        void teamToUserValidatesToo() {
            when(ai.post(any(), any(), eq(RecommendationResponse.class)))
                    .thenReturn(new RecommendationResponse());

            assertThatThrownBy(() -> client.teamToUser(teamToUserRequest()))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_ERROR);
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private RecommendationResponse response(RecommendationResponse.Recommendation... items) {
        RecommendationResponse response = new RecommendationResponse();
        response.setRecommendations(List.of(items));
        return response;
    }

    private RecommendationResponse.Recommendation recommendation(Long candidateId, Double score) {
        RecommendationResponse.Recommendation item = new RecommendationResponse.Recommendation();
        item.setCandidateId(candidateId);
        item.setScore(score);
        return item;
    }

    private RecommendationReasonResponse reasonResponse(String reason) {
        RecommendationReasonResponse response = new RecommendationReasonResponse();
        ReflectionTestUtils.setField(response, "reason", reason);
        return response;
    }

    private RecommendationReasonRequest reasonRequest() {
        return new RecommendationReasonRequest("후보 요약", "대상 요약", "점수 0.9");
    }

    private UserToTeamRecommendationRequest userToTeamRequest() {
        return new UserToTeamRecommendationRequest(
                new float[]{0.1f, 0.2f},
                new UserMetadata(List.of("디자이너"), List.of("Figma"), "입문", "온라인"),
                List.of());
    }

    private TeamToUserRecommendationRequest teamToUserRequest() {
        return new TeamToUserRecommendationRequest(
                new float[]{0.1f, 0.2f},
                new TeamMetadata(List.of("디자이너"), List.of("Figma"), "온라인", true),
                List.of());
    }
}

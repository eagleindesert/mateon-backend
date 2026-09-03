package com.example.mateon.teams.client;

import com.example.mateon.common.ai.AiServerProperties;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 팀 임베딩 클라이언트의 응답·실패 매핑을 고정한다.
 *
 * <p>
 * 요청 스키마는 {@link TeamEmbeddingRefreshRequestSerializationTest} 가 맡는다. 여기서는
 * {@code embedding_vector} 가 비면 저장부가 원인 불명 예외를 내기 전에 502 로 자르는지,
 * 그리고 연결 실패(503)와 4xx/5xx(502) 가 갈리는지를 본다. 이 클라이언트는
 * {@code AiCallTemplate} 을 안 타서 그 공통 테스트의 보장 밖에 있다.
 */
class TeamEmbeddingClientTest {

    private static final String BASE_URL = "http://ai.test:8001";
    private static final String REFRESH_URL = BASE_URL + "/internal/teams/embedding:refresh";

    private MockRestServiceServer server;
    private TeamEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setInternalSecret("test-secret");

        client = new TeamEmbeddingClient(restTemplate, properties);
    }

    @Nested
    @DisplayName("성공 매핑")
    class Success {

        @Test
        @DisplayName("snake_case 응답이 필드에 실린다")
        void mapsSnakeCaseResponse() {
            server.expect(requestTo(REFRESH_URL))
              .andExpect(method(HttpMethod.POST))
              .andExpect(header("X-Internal-Secret", "test-secret"))
              .andRespond(withSuccess("""
                {
                  "missing_fields": ["activity_goal"],
                  "embedding_text": "제목: 임베딩테스트 팀",
                  "embedding_vector": [0.1, 0.2],
                  "metadata": {
                    "recruiting_roles": ["디자이너"],
                    "required_skills": ["Figma"],
                    "activity_goal": "포트폴리오",
                    "activity_style": "온라인",
                    "activity_intensity": "주 2회",
                    "beginner_friendly": true
                  }
                }
                """, MediaType.APPLICATION_JSON));

            TeamEmbeddingRefreshResponse response = client.refresh(request());

            assertThat(response.getMissingFields()).containsExactly("activity_goal");
            assertThat(response.getEmbeddingText()).isEqualTo("제목: 임베딩테스트 팀");
            assertThat(response.getEmbeddingVector()).containsExactly(
              new double[]{0.1, 0.2}, within(1e-9));
            assertThat(response.getMetadata().getRecruitingRoles()).containsExactly("디자이너");
            assertThat(response.getMetadata().getRequiredSkills()).containsExactly("Figma");
            assertThat(response.getMetadata().getActivityGoal()).isEqualTo("포트폴리오");
            assertThat(response.getMetadata().getActivityStyle()).isEqualTo("온라인");
            assertThat(response.getMetadata().getActivityIntensity()).isEqualTo("주 2회");
            assertThat(response.getMetadata().getBeginnerFriendly()).isTrue();
        }
    }

    @Nested
    @DisplayName("실패 매핑")
    class Failures {

        @Test
        @DisplayName("본문이 없으면 502 — 저장부가 원인 불명 예외를 내기 전에 자른다")
        void emptyBodyIs502() {
            server.expect(requestTo(REFRESH_URL))
              .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

            assertRefreshFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("embedding_vector 가 없으면 502")
        void missingVectorIs502() {
            server.expect(requestTo(REFRESH_URL))
              .andRespond(withSuccess("""
                {"missing_fields":[],"embedding_text":"제목"}
                """, MediaType.APPLICATION_JSON));

            assertRefreshFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("연결 실패는 503 (재시도 가능)")
        void connectionFailureIs503() {
            server.expect(requestTo(REFRESH_URL))
              .andRespond(withException(new IOException("Connection refused")));

            assertRefreshFails(ErrorCode.AI_SERVER_UNAVAILABLE);
        }

        @Test
        @DisplayName("401 은 502 (시크릿 불일치)")
        void unauthorizedIs502() {
            server.expect(requestTo(REFRESH_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            assertRefreshFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("403 도 같은 시크릿 거절이다")
        void forbiddenIs502() {
            server.expect(requestTo(REFRESH_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

            assertRefreshFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("422 는 502 (요청 스키마 불일치)")
        void unprocessableIs502() {
            server.expect(requestTo(REFRESH_URL))
              .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));

            assertRefreshFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("그 외 5xx 도 502")
        void otherHttpErrorIs502() {
            server.expect(requestTo(REFRESH_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

            assertRefreshFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("본문이 깨져 있어도 502")
        void malformedBodyIs502() {
            server.expect(requestTo(REFRESH_URL))
              .andRespond(withSuccess("{", MediaType.APPLICATION_JSON));

            assertRefreshFails(ErrorCode.AI_SERVER_ERROR);
        }

        private void assertRefreshFails(ErrorCode expected) {
            assertThatThrownBy(() -> client.refresh(request()))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(expected);
        }
    }

    private static TeamEmbeddingRefreshRequest request() {
        return new TeamEmbeddingRefreshRequest(
          "제목: 임베딩테스트 팀",
          List.of("디자이너"),
          List.of("Figma"),
          "DESIGN_PHOTO_ART_VIDEO");
    }
}

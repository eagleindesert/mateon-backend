package com.example.mateon.matching.client.intent;

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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 의도 추출 호출의 요청 스키마와 응답 매핑을 고정한다.
 *
 * <p>여기서 가장 값비싼 건 <b>메시지 id 재채번</b>이다. AI 명세는 id 가 1부터 순서대로 늘어나길
 * 요구하는데, DB 의 {@code seq} 는 ASSISTANT 행 때문에 1,3,5... 로 건너뛴다. 그래서 보내기 직전에
 * 다시 매긴다. 이 재채번을 빼고 seq 를 그대로 넘겨도 AI 는 대개 답을 주기 때문에, 품질이
 * 나빠지는 것 말고는 아무 신호가 없다.
 *
 * <p>두 번째는 <b>snake_case 응답 매핑</b>이다. 이 프로젝트는 Jackson 2 와 3 이 함께 있어서
 * {@code @JsonNaming} 이 조용히 무시될 수 있고, 그러면 전 필드가 null 이 된다. 필드별
 * {@code @JsonProperty} 가 살아 있는지 실제 역직렬화로 확인한다 — 이게 깨지면 "AI 가 항상
 * 빈 응답을 준다"로 오진하게 된다.
 */
class IntentExtractionClientTest {

    private static final String BASE_URL = "http://ai.test:8001";
    private static final String EXTRACT_URL = BASE_URL + "/intents/extract";

    private MockRestServiceServer server;
    private IntentExtractionClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setInternalSecret("test-secret");

        client = new IntentExtractionClient(restTemplate, properties);
    }

    @Nested
    @DisplayName("요청 스키마")
    class Request {

        @Test
        @DisplayName("메시지 id 는 DB seq 가 아니라 1부터 다시 매긴다 (seq 는 ASSISTANT 행을 건너뛴다)")
        void renumbersMessageIdsFromOne() {
            server.expect(requestTo(EXTRACT_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Internal-Secret", "test-secret"))
                    .andExpect(jsonPath("$.messages[0].id").value(1))
                    .andExpect(jsonPath("$.messages[0].message").value("디자인 팀 찾아요"))
                    .andExpect(jsonPath("$.messages[1].id").value(2))
                    .andExpect(jsonPath("$.messages[1].message").value("주 2회 정도요"))
                    .andExpect(jsonPath("$.messages[2].id").value(3))
                    .andRespond(withSuccess(completedJson(), MediaType.APPLICATION_JSON));

            client.extract(List.of("디자인 팀 찾아요", "주 2회 정도요", "온라인이면 좋겠어요"));

            server.verify();
        }

        /**
         * 위 테스트는 <b>있어야 할 키</b>만 본다. 그것만으로는 예상 밖의 키가 늘어난 경우를
         * 못 잡는데, JSON 키 이름은 자바 소스 어디에도 적혀 있지 않아서(게터 이름 + 롬복이
         * 생성한 것 + 네이밍 전략의 결과다) 필드를 하나 추가하거나 이름을 바꾸는 것만으로
         * 조용히 늘어날 수 있다. 그래서 본문 전체를 한 번 못박는다.
         */
        @Test
        @DisplayName("본문 전체가 명세와 같다 (messages 말고 다른 키가 끼어들지 않는다)")
        void bodyMatchesSpec() {
            server.expect(requestTo(EXTRACT_URL))
                    .andExpect(content().json("""
                            {
                              "messages": [
                                {"id": 1, "message": "디자인 팀 찾아요"},
                                {"id": 2, "message": "주 2회 정도요"}
                              ]
                            }
                            """, JsonCompareMode.STRICT))
                    .andRespond(withSuccess(completedJson(), MediaType.APPLICATION_JSON));

            client.extract(List.of("디자인 팀 찾아요", "주 2회 정도요"));

            server.verify();
        }

        @Test
        @DisplayName("사용자 발화가 하나뿐이어도 id 는 1 이다")
        void singleMessage() {
            server.expect(requestTo(EXTRACT_URL))
                    .andExpect(jsonPath("$.messages[0].id").value(1))
                    .andExpect(jsonPath("$.messages.length()").value(1))
                    .andRespond(withSuccess(incompleteJson(), MediaType.APPLICATION_JSON));

            client.extract(List.of("안녕하세요"));

            server.verify();
        }
    }

    @Nested
    @DisplayName("응답 매핑 — snake_case 키가 살아 있어야 한다")
    class Response {

        @Test
        @DisplayName("추출 완료 응답의 모든 필드가 채워진다 (하나라도 null 이면 매핑이 끊긴 것이다)")
        void mapsCompletedResponse() {
            server.expect(requestTo(EXTRACT_URL))
                    .andRespond(withSuccess(completedJson(), MediaType.APPLICATION_JSON));

            IntentExtractResponse response = client.extract(List.of("m"));

            assertThat(response.getAssistantMessage()).isEqualTo("정리했어요!");
            assertThat(response.getMissingFields()).isEmpty();
            assertThat(response.isCompleted()).isTrue();
            assertThat(response.getEmbeddingText()).isEqualTo("디자인 협업");
            assertThat(response.getEmbeddingVector()).containsExactly(
                    new double[]{0.1, 0.2, 0.3}, within(1e-9));
        }

        @Test
        @DisplayName("extracted 하위 필드도 snake_case 로 매핑된다")
        void mapsExtractedFields() {
            server.expect(requestTo(EXTRACT_URL))
                    .andRespond(withSuccess(completedJson(), MediaType.APPLICATION_JSON));

            IntentExtractResponse.Extracted extracted = client.extract(List.of("m")).getExtracted();

            assertThat(extracted.getDesiredRoles()).containsExactly("디자이너");
            assertThat(extracted.getSkills()).containsExactly("Figma");
            assertThat(extracted.getInterests()).containsExactly("UX");
            assertThat(extracted.getActivityGoal()).isEqualTo("포트폴리오");
            assertThat(extracted.getActivityStyle()).isEqualTo("온라인");
            assertThat(extracted.getExperienceLevel()).isEqualTo("입문");
        }

        @Test
        @DisplayName("missing_fields 가 남아 있으면 미완료이고 임베딩은 오지 않는다")
        void incompleteResponse() {
            server.expect(requestTo(EXTRACT_URL))
                    .andRespond(withSuccess(incompleteJson(), MediaType.APPLICATION_JSON));

            IntentExtractResponse response = client.extract(List.of("m"));

            assertThat(response.isCompleted()).isFalse();
            assertThat(response.getMissingFields()).containsExactly("skills", "activity_goal");
            assertThat(response.getEmbeddingVector()).isNull();
        }

        @Test
        @DisplayName("AI 가 필드를 추가해도 깨지지 않는다 (@JsonIgnoreProperties)")
        void toleratesUnknownFields() {
            server.expect(requestTo(EXTRACT_URL))
                    .andRespond(withSuccess("""
                            {"assistant_message":"안녕","missing_fields":["skills"],
                             "new_field_from_ai":{"nested":true}}
                            """, MediaType.APPLICATION_JSON));

            assertThat(client.extract(List.of("m")).getAssistantMessage()).isEqualTo("안녕");
        }
    }

    @Nested
    @DisplayName("실패 매핑")
    class Failures {

        @Test
        @DisplayName("assistant_message 가 없으면 502 — 프론트가 보여줄 문구가 없다")
        void missingAssistantMessageIs502() {
            server.expect(requestTo(EXTRACT_URL))
                    .andRespond(withSuccess("{\"missing_fields\":[\"skills\"]}", MediaType.APPLICATION_JSON));

            assertExtractFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("본문이 JSON null 이면 502")
        void nullBodyIs502() {
            server.expect(requestTo(EXTRACT_URL))
                    .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

            assertExtractFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("연결 실패는 503 (재시도 가능)")
        void connectionFailureIs503() {
            server.expect(requestTo(EXTRACT_URL))
                    .andRespond(withException(new IOException("Connection refused")));

            assertExtractFails(ErrorCode.AI_SERVER_UNAVAILABLE);
        }

        @Test
        @DisplayName("401 은 502 (시크릿 불일치)")
        void unauthorizedIs502() {
            server.expect(requestTo(EXTRACT_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            assertExtractFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("422 는 502 (요청 스키마 불일치)")
        void unprocessableIs502() {
            server.expect(requestTo(EXTRACT_URL))
                    .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));

            assertExtractFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("403 도 시크릿 거절이라 502")
        void forbiddenIs502() {
            server.expect(requestTo(EXTRACT_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

            assertExtractFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("그 외 5xx 도 502")
        void otherHttpErrorIs502() {
            server.expect(requestTo(EXTRACT_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

            assertExtractFails(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("본문이 깨져 있어도 502")
        void malformedBodyIs502() {
            server.expect(requestTo(EXTRACT_URL))
                    .andRespond(withSuccess("{", MediaType.APPLICATION_JSON));

            assertExtractFails(ErrorCode.AI_SERVER_ERROR);
        }

        private void assertExtractFails(ErrorCode expected) {
            assertThatThrownBy(() -> client.extract(List.of("m")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(expected);
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private String completedJson() {
        return """
                {
                  "assistant_message": "정리했어요!",
                  "missing_fields": [],
                  "embedding_text": "디자인 협업",
                  "embedding_vector": [0.1, 0.2, 0.3],
                  "extracted": {
                    "desired_roles": ["디자이너"],
                    "skills": ["Figma"],
                    "interests": ["UX"],
                    "activity_goal": "포트폴리오",
                    "activity_style": "온라인",
                    "experience_level": "입문"
                  }
                }
                """;
    }

    private String incompleteJson() {
        return """
                {
                  "assistant_message": "어떤 기술을 쓰시나요?",
                  "missing_fields": ["skills", "activity_goal"]
                }
                """;
    }
}

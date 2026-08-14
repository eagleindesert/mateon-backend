package com.example.mateon.common.ai;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AI 서버 호출의 공통 규약을 고정한다. 이 클래스가 깨지면 matching 도메인 전체가 함께 깨진다.
 *
 * <p>가장 중요한 건 <b>503 과 502 의 분기</b>다. 프론트는 이 둘을 보고 재시도 여부를 정한다 —
 * 연결 실패(AI 서버가 아직 안 떴거나 재시작 중)는 잠시 뒤 다시 하면 되니 503, AI 가 응답은
 * 했지만 내용이 틀린 것(4xx/5xx, 빈 본문)은 다시 해도 같으니 502다. 이 구분은 코드 상으로는
 * {@code catch} 블록 순서 한 줄 차이라 리팩터링 중에 뭉개지기 쉽다.
 *
 * <p>두 번째는 <b>{@code X-Internal-Secret} 헤더</b>. 빠지면 AI 서버가 401 로 거절하는데,
 * 우리 쪽 로그에는 502 로만 남아 "AI 서버가 이상하다"로 오진하게 된다.
 *
 * <p>세 번째는 <b>200 인데 본문이 빈 경우도 실패</b>라는 것. 이게 없으면 null 이 그대로
 * 흘러가 한참 뒤 엉뚱한 곳에서 NPE 로 터진다.
 */
class AiCallTemplateTest {

    private static final String BASE_URL = "http://ai.test:8001";
    private static final String SECRET = "test-internal-secret";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private AiCallTemplate template;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setInternalSecret(SECRET);

        template = new AiCallTemplate(restTemplate, properties);
    }

    @Nested
    @DisplayName("요청 형태")
    class RequestShape {

        @Test
        @DisplayName("URL 은 baseUrl + path 를 그대로 이어 붙인다")
        void concatenatesBaseUrlAndPath() {
            server.expect(requestTo(BASE_URL + "/recommendations/reason"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(ok());

            template.post("/recommendations/reason", new Probe("x"), Probe.class);

            server.verify();
        }

        @Test
        @DisplayName("모든 요청에 X-Internal-Secret 헤더가 붙는다 (없으면 AI 가 401 로 거절한다)")
        void alwaysSendsInternalSecret() {
            server.expect(requestTo(BASE_URL + "/p"))
                    .andExpect(header("X-Internal-Secret", SECRET))
                    .andRespond(ok());

            template.post("/p", new Probe("x"), Probe.class);

            server.verify();
        }

        @Test
        @DisplayName("post 는 application/json 으로 보낸다")
        void postUsesJson() {
            server.expect(requestTo(BASE_URL + "/p"))
                    .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith(
                            MediaType.APPLICATION_JSON_VALUE)))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"value\":\"x\"")))
                    .andRespond(ok());

            template.post("/p", new Probe("x"), Probe.class);

            server.verify();
        }

        @Test
        @DisplayName("postMultipart 는 multipart/form-data 로 보낸다 (파일 파트가 필요한 엔드포인트용)")
        void multipartUsesFormData() {
            server.expect(requestTo(BASE_URL + "/contests/extract-image"))
                    .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith(
                            MediaType.MULTIPART_FORM_DATA_VALUE)))
                    .andExpect(header("X-Internal-Secret", SECRET))
                    .andRespond(ok());

            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("image", new ByteArrayResource(new byte[]{1, 2, 3}) {
                @Override
                public String getFilename() {
                    return "poster.png";
                }
            });

            template.postMultipart("/contests/extract-image", parts, Probe.class);

            server.verify();
        }
    }

    @Nested
    @DisplayName("응답 처리")
    class ResponseHandling {

        @Test
        @DisplayName("정상 응답은 역직렬화해서 돌려준다")
        void returnsBody() {
            server.expect(requestTo(BASE_URL + "/p"))
                    .andRespond(withSuccess("{\"value\":\"결과\"}", MediaType.APPLICATION_JSON));

            assertThat(template.post("/p", new Probe("x"), Probe.class).getValue()).isEqualTo("결과");
        }

        @Test
        @DisplayName("200 인데 본문이 비면 502 다 — null 을 흘려보내면 한참 뒤 NPE 로 터진다")
        void emptyBodyIs502() {
            server.expect(requestTo(BASE_URL + "/p"))
                    .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON));

            assertAiError(ErrorCode.AI_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("실패 매핑 — 503(재시도 가능) 과 502(재시도 무의미) 를 가른다")
    class FailureMapping {

        @Test
        @DisplayName("연결 실패·타임아웃은 503 이다 (프론트가 재시도해도 되는 신호)")
        void connectionFailureIs503() {
            server.expect(requestTo(BASE_URL + "/p"))
                    .andRespond(withException(new IOException("Connection refused")));

            assertAiError(ErrorCode.AI_SERVER_UNAVAILABLE);
        }

        @Test
        @DisplayName("401 은 502 다 (시크릿 불일치 — 재시도해도 같다)")
        void unauthorizedIs502() {
            server.expect(requestTo(BASE_URL + "/p"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"detail\":\"invalid internal secret\"}"));

            assertAiError(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("403 도 502 다")
        void forbiddenIs502() {
            server.expect(requestTo(BASE_URL + "/p")).andRespond(withStatus(HttpStatus.FORBIDDEN));

            assertAiError(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("422 는 502 다 (요청 스키마가 AI 명세와 어긋남)")
        void unprocessableIs502() {
            server.expect(requestTo(BASE_URL + "/p"))
                    .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"detail\":[{\"loc\":[\"body\"],\"msg\":\"field required\"}]}"));

            assertAiError(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("AI 쪽 500 도 502 다 — 우리 서버 오류로 보이면 안 된다")
        void aiServerErrorIs502() {
            server.expect(requestTo(BASE_URL + "/p"))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

            assertAiError(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("응답 JSON 이 깨져 역직렬화에 실패해도 502 다")
        void malformedJsonIs502() {
            server.expect(requestTo(BASE_URL + "/p"))
                    .andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));

            assertAiError(ErrorCode.AI_SERVER_ERROR);
        }
    }

    // --- 헬퍼 ---------------------------------------------------------------

    private void assertAiError(ErrorCode expected) {
        assertThatThrownBy(() -> template.post("/p", new Probe("x"), Probe.class))
                .isInstanceOf(MateonException.class)
                .extracting("errorCode").isEqualTo(expected);
    }

    private static org.springframework.test.web.client.response.DefaultResponseCreator ok() {
        return withSuccess("{\"value\":\"ok\"}", MediaType.APPLICATION_JSON);
    }

    /** 이 테스트만 쓰는 최소 페이로드. 실제 DTO 를 쓰면 그쪽 변경에 끌려다닌다. */
    static class Probe {
        @JsonProperty("value")
        private String value;

        Probe() {
        }

        Probe(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}

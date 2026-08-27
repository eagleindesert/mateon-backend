package com.example.mateon.matching.client.selection;

import com.example.mateon.common.ai.AiCallTemplate;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.domain.SelectionDirection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 선택 피드백 클라이언트가 <b>어느 경로로, 어떤 모양으로</b> 보내는지를 고정한다.
 * (인증 헤더·상태코드 매핑은 AiCallTemplateTest 가 이미 잡고 있으므로 여기서는 목을 쓴다 —
 * {@code RecommendationClientTest} 와 같은 방식이다.)
 *
 * <p>
 * 직렬화를 검증하는 테스트가 있는 이유가 이 클래스의 존재 이유와 같다. component_scores 는
 * <b>추천 때 받은 원문을 한 글자도 바꾸지 말고</b> 되돌려줘야 하는 값인데, 이걸 String 필드로
 * 들고 다니므로 {@code @JsonRawValue} 하나가 빠지면 조용히 <b>따옴표로 감싼 문자열</b>이 나간다.
 * AI 쪽에서는 422 도 아니고 그냥 이상한 데이터로 남아, 피드백 품질이 나빠질 때까지 아무도 모른다.
 */
class SelectionEventClientTest {

    private static final String PATH = "/selection-events";

    private AiCallTemplate ai;
    private SelectionEventClient client;

    @BeforeEach
    void setUp() {
        ai = mock(AiCallTemplate.class);
        client = new SelectionEventClient(ai);
    }

    @Nested
    @DisplayName("경로")
    class Path {

        @Test
        @DisplayName("/selection-events 로 간다 (제안 조립과 독립된 엔드포인트다)")
        void sendsToSelectionEvents() {
            when(ai.post(eq(PATH), any(), eq(SelectionEventResponse.class)))
              .thenReturn(response(true));

            client.send(request());

            verify(ai).post(eq(PATH), any(), eq(SelectionEventResponse.class));
        }
    }

    @Nested
    @DisplayName("응답 처리")
    class Responses {

        @Test
        @DisplayName("accepted=false 여도 예외로 만들지 않는다 — 저장 확인이 아니라 접수 확인이다")
        void falseAcceptedIsNotAnError() {
            when(ai.post(any(), any(), eq(SelectionEventResponse.class))).thenReturn(response(false));

            assertThatCode(() -> client.send(request())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepted 가 없어도 예외로 만들지 않는다 (경고만 남긴다)")
        void missingAcceptedIsNotAnError() {
            when(ai.post(any(), any(), eq(SelectionEventResponse.class))).thenReturn(response(null));

            assertThatCode(() -> client.send(request())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AI 장애는 그대로 올려보낸다 — 삼킬지는 흐름을 아는 호출자가 정한다")
        void propagatesAiFailure() {
            when(ai.post(any(), any(), eq(SelectionEventResponse.class)))
              .thenThrow(new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE));

            assertThatThrownBy(() -> client.send(request()))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("요청 직렬화 — 명세 키와 원문 보존")
    class Serialization {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("component_scores 가 원문 JSON 그대로 나간다 (문자열로 감싸지 않는다)")
        void componentScoresStayRawJson() throws Exception {
            String json = objectMapper.writeValueAsString(request());

            // 따옴표로 감싸였다면 "component_scores":"{\"similarity\":0.8}" 처럼 나온다.
            assertThat(json).contains("\"component_scores\":{\"similarity\":0.8,\"role_match\":1.0}");
            assertThat(objectMapper.readTree(json)
              .at("/selection_context/shown_candidates/0/component_scores/similarity")
              .asDouble()).isEqualTo(0.8);
        }

        @Test
        @DisplayName("component_scores 가 없던 추천이면 null 로 나간다 (지어내지 않는다)")
        void missingComponentScoresSerializeToNull() throws Exception {
            SelectionEventRequest withoutScores = new SelectionEventRequest(
              SelectionDirection.USER_TO_TEAM, 17L,
              new SelectionEventRequest.SelectionContext("key", Map.of(),
                List.of(new SelectionEventRequest.ShownCandidate(17L, 0.92, null))));

            assertThat(objectMapper.readTree(objectMapper.writeValueAsString(withoutScores))
              .at("/selection_context/shown_candidates/0/component_scores")
              .isNull()).isTrue();
        }

        @Test
        @DisplayName("최상위 키가 명세 그대로다 (direction/selected_candidate_id/selection_context)")
        void topLevelKeysMatchSpec() throws Exception {
            var tree = objectMapper.readTree(objectMapper.writeValueAsString(request()));

            assertThat(tree.get("direction").asText()).isEqualTo("USER_TO_TEAM");
            assertThat(tree.get("selected_candidate_id").asLong()).isEqualTo(17L);
            assertThat(tree.at("/selection_context/idempotency_key").asText()).isEqualTo("key-1");
            assertThat(tree.at("/selection_context/chooser_fields/experience_level").asText())
              .isEqualTo("beginner");
            assertThat(tree.at("/selection_context/shown_candidates/0/total_score").asDouble())
              .isEqualTo(0.92);
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private SelectionEventRequest request() {
        return new SelectionEventRequest(SelectionDirection.USER_TO_TEAM, 17L,
          new SelectionEventRequest.SelectionContext(
            "key-1",
            Map.of("experience_level", "beginner"),
            List.of(new SelectionEventRequest.ShownCandidate(17L, 0.92,
              "{\"similarity\":0.8,\"role_match\":1.0}"))));
    }

    private SelectionEventResponse response(Boolean accepted) {
        SelectionEventResponse response = new SelectionEventResponse();
        ReflectionTestUtils.setField(response, "accepted", accepted);
        return response;
    }
}

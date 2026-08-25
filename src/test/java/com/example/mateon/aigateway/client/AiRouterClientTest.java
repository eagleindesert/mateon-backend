package com.example.mateon.aigateway.client;

import com.example.mateon.aichat.domain.RoutableDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 라우터가 LLM 응답을 우리 타입으로 옮기는 구간과, <b>실패하면 반드시 통과시키는</b> 규약을 고정한다.
 *
 * <p>
 * 목을 {@link ChatModel} 에 두는 이유: {@code ChatClient} 는 fluent 라 그걸 목으로 만들면
 * {@code prompt().user().call().entity()} 체인을 흉내 내는 데 그치고, 정작 위험한 부분 —
 * Spring AI 의 BeanOutputConverter 가 {@link RouteDecision} record 와 {@link RoutableDomain}
 * enum 으로 스키마를 만들고 응답을 역직렬화하는 것 — 은 하나도 거치지 않는다. ChatModel 은
 * 그 변환의 <i>아래</i>에 있어서, 여기에 목을 두면 변환이 실제로 돈다.
 *
 * <p>
 * HTTP 경계(MockRestServiceServer)로 더 내리지 않은 이유는 Spring AI 2.0 의 OpenAI 연동이
 * Spring 의 RestClient 가 아니라 공식 OpenAI Java SDK(OkHttp)를 쓰기 때문이다. 거기까지 내려가면
 * 검증 대상이 우리 코드가 아니라 Spring AI 의 와이어 포맷이 된다.
 *
 * <p>
 * 가장 중요한 건 마지막 묶음이다. 이 클래스는 <b>어떤 경우에도 예외를 밖으로 내보내지
 * 않는다</b>. 라우터가 죽었다고 챗봇 전체가 죽으면 게이트웨이 도입 전보다 나빠지기 때문이다.
 */
class AiRouterClientTest {

    private ChatModel chatModel;
    private AiRouterClient client;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        // getOptions() 를 반드시 스텁해야 한다. ChatClient 가 요청을 조립하며 이걸 먼저 부르는데,
        // 목의 기본값 null 이 그 안에서 NPE 를 내고 → 우리 폴백에 걸려 → 모든 테스트가
        // "실패했으므로 매칭"으로 통과해 버린다. 즉 스텁이 없으면 폴백 테스트조차 가짜로 통과한다.
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());

        // 프롬프트 조립은 운영과 같은 경로를 탄다 — 여기서 프롬프트를 따로 만들면
        // 아래 "카탈로그가 프롬프트에 실린다" 테스트가 자기 자신을 검사하게 된다.
        client = new AiRouterClient(chatModel);
    }

    @Nested
    @DisplayName("프롬프트 — 카탈로그가 곧 프롬프트다")
    class PromptAssembly {

        @Test
        @DisplayName("모든 도메인 상수와 설명이 프롬프트에 실린다 — 카탈로그만 늘리고 프롬프트를 잊으면 여기서 깨진다")
        void promptCarriesEveryDomain() {
            givenModelReplies("{\"domain\":\"MATCHING_INTENT\",\"assistantMessage\":\"\"}");

            client.classify("백엔드 팀 찾아요");

            ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel).call(captor.capture());
            String sent = captor.getValue().getContents();

            for (RoutableDomain domain : RoutableDomain.values()) {
                assertThat(sent).contains(domain.name());
                assertThat(sent).contains(domain.getDescription());
            }
        }

        @Test
        @DisplayName("사용자 발화가 그대로 실린다")
        void promptCarriesUserMessage() {
            givenModelReplies("{\"domain\":\"OUT_OF_SCOPE\",\"assistantMessage\":\"도와드리기 어려워요\"}");

            client.classify("오늘 서울 날씨 어때?");

            ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel).call(captor.capture());
            assertThat(captor.getValue().getContents()).contains("오늘 서울 날씨 어때?");
        }
    }

    @Nested
    @DisplayName("정상 응답 파싱")
    class Parsing {

        @Test
        @DisplayName("JSON 응답이 domain enum 과 문구로 풀린다")
        void parsesDecision() {
            givenModelReplies("{\"domain\":\"OUT_OF_SCOPE\",\"assistantMessage\":\"그 주제는 어려워요\"}");

            RouteDecision decision = client.classify("파이썬 문법 알려줘");

            assertThat(decision.domain()).isEqualTo(RoutableDomain.OUT_OF_SCOPE);
            assertThat(decision.assistantMessage()).isEqualTo("그 주제는 어려워요");
        }

        @Test
        @DisplayName("위임되는 판정은 문구가 비어 있어도 된다 (답변은 도메인 AI 가 만든다)")
        void delegatableDecisionMayHaveNoMessage() {
            givenModelReplies("{\"domain\":\"MATCHING_INTENT\",\"assistantMessage\":\"\"}");

            assertThat(client.classify("백엔드 팀 찾아요").domain())
              .isEqualTo(RoutableDomain.MATCHING_INTENT);
        }
    }

    @Nested
    @DisplayName("실패 — 전부 매칭으로 통과시킨다. 최악의 경우가 '게이트웨이 도입 전과 똑같음' 이어야 한다")
    class Fallback {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "{\"domain\":\"WEATHER_FORECAST\",\"assistantMessage\":\"맑아요\"}", // enum 밖의 값
            "죄송하지만 JSON 으로 답할 수 없습니다", // JSON 이 아님
            "{\"assistantMessage\":\"문구만 있음\"}", // domain 누락
            "{}", // 빈 객체
            "" // 빈 응답
        })
        @DisplayName("망가진 응답은 매칭으로 통과시킨다")
        void brokenResponsesPassThrough(String body) {
            givenModelReplies(body);

            RouteDecision decision = client.classify("아무 말");

            assertThat(decision.domain()).isEqualTo(RoutableDomain.MATCHING_INTENT);
        }

        @Test
        @DisplayName("모델 호출이 예외를 던져도 예외가 밖으로 나가지 않는다 (키 없음·타임아웃·장애가 전부 여기다)")
        void modelFailureDoesNotPropagate() {
            when(chatModel.call(any(Prompt.class)))
              .thenThrow(new RuntimeException("401 Unauthorized"));

            assertThatCode(() -> assertThat(client.classify("아무 말").domain())
              .isEqualTo(RoutableDomain.MATCHING_INTENT))
              .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Spring AI 모델 빈이 없으면 모델을 부르지 않고 통과시킨다 (키 없는 환경에서도 앱은 뜬다)")
        void noModelMeansPassThrough() {
            AiRouterClient disabled = new AiRouterClient((ChatModel) null);

            assertThat(disabled.classify("백엔드 팀 찾아요").domain())
              .isEqualTo(RoutableDomain.MATCHING_INTENT);
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    private void givenModelReplies(String content) {
        when(chatModel.call(any(Prompt.class)))
          .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }
}

package com.example.mateon.matching.client.selection;

import com.example.mateon.common.ai.AiCallTemplate;
import com.example.mateon.matching.domain.SelectionDirection;
import com.example.mateon.support.AiStubSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 선택 피드백 응답이 ai-stub 에서 실제로 돌아와 DTO 로 채워지는지 확인한다.
 *
 * <p>
 * 여기만 검증 방식이 다르다. {@link SelectionEventClient#send} 는 void 라 클라이언트를
 * 거쳐서는 {@code accepted} 가 채워졌는지 볼 방법이 없다 — 값이 null 이어도 경고 로그만
 * 남고 정상 종료한다. 그래서 {@link AiCallTemplate#post} 를 직접 불러 응답 DTO 를 받는
 * 테스트를 따로 둔다. 클라이언트 경유 테스트는 <b>왕복 자체가 성립하는지</b>만 본다.
 *
 * <p>
 * {@code accepted} 가 래퍼 타입인 이유가 DTO 주석에 적혀 있다 — "AI 가 안 줬다"와 "false 를
 * 줬다"를 구분하기 위해서다. 그 구분이 성립하려면 실제로 값이 채워져 와야 하는데,
 * 그걸 확인하는 자리가 지금까지 없었다.
 */
class SelectionEventLiveTest {

    private static final String PATH = "/selection-events";

    private AiCallTemplate ai;
    private SelectionEventClient client;

    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        ai = AiStubSupport.aiCallTemplate();
        client = new SelectionEventClient(ai);
    }

    @Test
    @DisplayName("accepted 가 true 로 채워져 온다 (안 왔다와 false 를 구분할 수 있어야 한다)")
    void acceptedIsMapped() {
        SelectionEventResponse response = ai.post(PATH, request(), SelectionEventResponse.class);

        assertThat(response.getAccepted()).isNotNull();
        assertThat(response.getAccepted()).isTrue();
    }

    @Test
    @DisplayName("클라이언트를 거친 왕복이 예외 없이 끝난다")
    void sendCompletes() {
        assertThatCode(() -> client.send(request())).doesNotThrowAnyException();
    }

    // --- 픽스처 -------------------------------------------------------------
    // SelectionEventClientTest 와 같은 값이다. component_scores 는 추천 때 받은 원문을
    // 그대로 되돌려 주는 자리라, 스텁이 이 문자열이 객체로 도착하는지 콘솔에서 대조한다.

    private SelectionEventRequest request() {
        return new SelectionEventRequest(SelectionDirection.USER_TO_TEAM, 17L,
          new SelectionEventRequest.SelectionContext(
            "key-1",
            Map.of("experience_level", "beginner"),
            List.of(new SelectionEventRequest.ShownCandidate(17L, 0.92,
              "{\"similarity\":0.8,\"role_match\":1.0}"))));
    }
}

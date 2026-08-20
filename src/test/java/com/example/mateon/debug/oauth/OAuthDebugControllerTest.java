package com.example.mateon.debug.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로컬 전용 카카오 인가코드 수신기.
 *
 * <p>
 * 디버그 도구지만 <b>동작이 깨지면 카카오 로그인을 손으로 검증할 방법이 사라진다</b>.
 * 인가코드는 일회용이고 수 분 안에 만료되므로, 셸 스크립트가 읽는 시점에 테이블에는 <b>가장
 * 최근 코드 하나만</b> 있어야 한다.
 *
 * <p>
 * 그래서 {@code deleteAllInBatch()} 가 {@code save()} <b>보다 먼저</b>여야 한다. 순서가
 * 뒤집히면 방금 저장한 코드까지 지워져 테이블이 비고, 스크립트는 "코드가 없다"며 실패한다.
 * 반대로 삭제가 빠지면 옛 코드가 남아 스크립트가 만료된 코드를 집어 든다 — 둘 다 브라우저
 * 화면에는 "저장 완료"가 멀쩡히 뜬다.
 *
 * <p>
 * 응답이 {@code text/html} 인 것도 확인한다. 브라우저 리다이렉트의 종착지라, JSON 으로
 * 바뀌면 사용자가 원시 JSON 을 보게 된다.
 *
 * <p>
 * {@code @ConditionalOnProperty} 로 기본 미등록이라는 안전장치는 여기서 검증하지 않는다 —
 * 빈 등록은 컨텍스트의 몫이고, standalone MockMvc 는 조건부 등록을 거치지 않는다.
 */
class OAuthDebugControllerTest {

    private OAuthDebugCodeRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = mock(OAuthDebugCodeRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OAuthDebugController(repository)).build();
    }

    @Test
    @DisplayName("옛 코드를 지운 뒤에 저장한다 — 순서가 뒤집히면 테이블이 빈다")
    void deletesBeforeSaving() throws Exception {
        mockMvc.perform(get("/debug/oauth").param("code", "abc123"))
          .andExpect(status().isOk());

        InOrder order = inOrder(repository);
        order.verify(repository).deleteAllInBatch();
        order.verify(repository).save(any());
    }

    @Test
    @DisplayName("받은 인가코드를 그대로 저장한다")
    void savesCodeAsIs() throws Exception {
        mockMvc.perform(get("/debug/oauth").param("code", "abc123"));

        ArgumentCaptor<OAuthDebugCode> saved = ArgumentCaptor.forClass(OAuthDebugCode.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("응답은 브라우저가 읽을 HTML 이고 코드가 화면에 보인다")
    void returnsHtml() throws Exception {
        mockMvc.perform(get("/debug/oauth").param("code", "abc123"))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith("text/html"))
          .andExpect(content().string(org.hamcrest.Matchers.containsString("abc123")))
          .andExpect(content().string(org.hamcrest.Matchers.containsString("get-kakao-token.ps1")));
    }

    @Test
    @DisplayName("code 파라미터가 없으면 저장하지 않는다")
    void requiresCode() throws Exception {
        // 전용 예외 핸들러 없이 호출하므로 상태코드는 보지 않는다. 중요한 건
        // 코드 없이 deleteAllInBatch() 가 불려 기존 코드를 날리지 않는 것이다.
        try {
            mockMvc.perform(get("/debug/oauth"));
        } catch (Exception ignored) {
            // MissingServletRequestParameterException 이 그대로 올라온다.
        }

        verify(repository, never()).deleteAllInBatch();
        verify(repository, never()).save(any());
    }
}

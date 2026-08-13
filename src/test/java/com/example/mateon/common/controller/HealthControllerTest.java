package com.example.mateon.common.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 헬스체크. 로직이 없는 세 줄짜리 컨트롤러인데도 테스트가 있는 이유는 <b>경로 자체가 계약</b>
 * 이기 때문이다.
 *
 * <p>배포 환경의 로드밸런서/컨테이너 헬스체크가 이 두 경로를 찌른다. 누가 이걸
 * {@code /api/health} 아래로 정리하거나 {@code @RequestMapping} 을 클래스에 붙이면 컴파일도
 * 되고 앱도 뜨는데 <b>배포가 롤백된다</b> — 헬스체크 404 는 "인스턴스가 죽었다"로 읽히므로
 * 새 버전이 트래픽을 못 받고 계속 재시작한다. 로컬에서는 아무 증상이 없다.
 *
 * <p>{@code /} 와 {@code /health} 둘 다 유지되는 것도 계약이다. 하나는 브라우저로 열어 보는
 * 용도(버전 표시), 하나는 기계용이다.
 */
class HealthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController()).build();
    }

    @Test
    @DisplayName("루트 / 가 200 UP 을 낸다 — 이 경로가 배포 헬스체크 대상이다")
    void rootIsHealthy() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.message").value("Mateon Backend API is running"))
                .andExpect(jsonPath("$.data.version").value("1.0.0"));
    }

    @Test
    @DisplayName("/health 도 함께 살아 있어야 한다 (기계용 경로)")
    void healthPathIsHealthy() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    @DisplayName("인증 없이 응답한다 (헬스체크에 토큰을 붙일 수 없다)")
    void needsNoPrincipal() throws Exception {
        // principal 을 주지 않고 호출한다. 컨트롤러가 Authentication 을 요구하게 되면
        // 배포 헬스체크가 전부 실패한다.
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }
}

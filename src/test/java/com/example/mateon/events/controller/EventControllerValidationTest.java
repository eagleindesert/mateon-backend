package com.example.mateon.events.controller;

import com.example.mateon.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 잘못된 category 값이 400 으로 나가는지 확인한다.
 *
 * <p>
 * 한때 이 경로들은 500 "서버 오류가 발생했습니다" 를 냈다. enum 변환 실패(본문은
 * HttpMessageNotReadableException, 쿼리 파라미터는 MethodArgumentTypeMismatchException)를
 * 받아주는 핸들러가 GlobalExceptionHandler 에 없어 catch-all 로 떨어졌기 때문이다.
 * 클라이언트 입력 오류가 서버 장애로 보이면 프론트가 원인을 찾을 수 없다.
 *
 * <p>
 * 의존성에 null 을 넣는다. 여기서 검증하는 요청들은 전부 <b>인자 바인딩 단계에서</b> 실패해
 * 컨트롤러 메서드 본문에 진입하지 않으므로 리포지토리/서비스가 호출될 일이 없다.
 */
class EventControllerValidationTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EventController(null, null, null, null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("등록 시 category 가 enum 에 없는 값이면 400 과 허용 값 안내를 준다")
    void createRejectsUnknownCategory() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(APPLICATION_JSON)
                        .content("{\"category\":\"공모전\",\"title\":\"잘못된 카테고리\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.category").value(
                        org.hamcrest.Matchers.containsString("CONTEST, EXTERNAL, SCHOOL")));
    }

    @Test
    @DisplayName("등록 시 category 대소문자가 다르면 400 (enum 매칭은 대소문자를 구분한다)")
    void createRejectsLowercaseCategory() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(APPLICATION_JSON)
                        .content("{\"category\":\"contest\",\"title\":\"소문자\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.category").exists());
    }

    @Test
    @DisplayName("검색 시 category 쿼리 파라미터가 enum 에 없는 값이면 400")
    void searchRejectsUnknownCategory() throws Exception {
        mockMvc.perform(get("/api/events/search").param("category", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.category").value(
                        org.hamcrest.Matchers.containsString("CONTEST, EXTERNAL, SCHOOL")));
    }

    @Test
    @DisplayName("등록 시 field 가 분야 목록에 없는 값이면 400")
    void createRejectsUnknownField() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(APPLICATION_JSON)
                        .content("{\"category\":\"CONTEST\",\"title\":\"분야 오타\",\"field\":\"IT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.field").value(
                        org.hamcrest.Matchers.containsString("SCIENCE_ENGINEERING_TECH_IT")));
    }

    @Test
    @DisplayName("검색 시 field 쿼리 파라미터가 분야 목록에 없는 값이면 400")
    void searchRejectsUnknownField() throws Exception {
        mockMvc.perform(get("/api/events/search").param("field", "TRAVEL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.field").exists());
    }
}

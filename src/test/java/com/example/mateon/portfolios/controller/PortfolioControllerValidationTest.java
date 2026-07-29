package com.example.mateon.portfolios.controller;

import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.portfolios.dto.PortfolioSummaryResponseDTO;
import com.example.mateon.portfolios.service.PortfolioSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 요약 엔드포인트의 요청 규약을 고정한다.
 *
 * <p>파트 이름({@code pdf_file})이 어긋나면 400 이어야 한다 — 핸들러가 없으면
 * MissingServletRequestPartException 이 catch-all 로 떨어져 500 이 되고, 그러면 프론트는
 * 자기 요청이 잘못됐다는 걸 알 수 없다.
 *
 * <p>인증 자체는 SecurityConfig 가 강제하므로 여기서 검증하지 않는다(standalone MockMvc 에는
 * 시큐리티 필터가 없다). 다만 컨트롤러가 Authentication 에서 userId 를 꺼내 서비스로 넘기는지는
 * 확인한다 — 여기가 어긋나면 남의 캐시를 보게 된다.
 */
class PortfolioControllerValidationTest {

    private static final byte[] PDF_BYTES = "%PDF-1.7\n내용".getBytes(StandardCharsets.UTF_8);

    /** JwtAuthenticationFilter 가 만드는 것과 같은 형태 — principal 이 userId 문자열이다. */
    private static final Authentication LOGGED_IN =
            new UsernamePasswordAuthenticationToken("42", null, List.of());

    private MockMvc mockMvc;
    private PortfolioSummaryService summaryService;

    @BeforeEach
    void setUp() {
        summaryService = mock(PortfolioSummaryService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PortfolioController(summaryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockMultipartFile part(String partName, String filename) {
        return new MockMultipartFile(partName, filename, "application/pdf", PDF_BYTES);
    }

    @Test
    @DisplayName("요약 결과를 200 으로 돌려준다 (저장 자원을 만드는 게 아니라 201 이 아니다)")
    void returnsSummary() throws Exception {
        when(summaryService.summarize(eq(42L), any()))
                .thenReturn(new PortfolioSummaryResponseDTO("- 프로젝트 A\n\n요약\n설명"));

        mockMvc.perform(multipart("/api/portfolios/summarize")
                        .file(part("pdf_file", "portfolio.pdf"))
                        .principal(LOGGED_IN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.summary").value("- 프로젝트 A\n\n요약\n설명"))
                // pdf_id 는 백엔드 내부 식별자라 응답에 실리지 않는다.
                .andExpect(jsonPath("$.data.pdfId").doesNotExist());
    }

    @Test
    @DisplayName("파트 이름이 pdf_file 이 아니면 400 (핸들러가 없으면 500 이 된다)")
    void rejectsWrongPartName() throws Exception {
        mockMvc.perform(multipart("/api/portfolios/summarize")
                        .file(part("file", "portfolio.pdf"))
                        .principal(LOGGED_IN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}

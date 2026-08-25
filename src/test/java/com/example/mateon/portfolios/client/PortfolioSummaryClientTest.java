package com.example.mateon.portfolios.client;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.ai.AiCallTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 로 나가는 멀티파트의 모양을 고정한다.
 *
 * <p>
 * 여기서 어긋나는 실수는 전부 런타임 422 로만 드러나고(FastAPI 가 파트를 UploadFile 로 인식하지
 * 못한다), 로그만 봐서는 원인을 짚기 어렵다. 파트 이름과 파일명은 그래서 테스트로 못박는다.
 */
class PortfolioSummaryClientTest {

    private static final byte[] PDF_BYTES = "%PDF-1.7\n내용".getBytes(StandardCharsets.UTF_8);

    private AiCallTemplate aiCallTemplate;
    private PortfolioSummaryClient client;

    @BeforeEach
    void setUp() {
        aiCallTemplate = mock(AiCallTemplate.class);
        client = new PortfolioSummaryClient(aiCallTemplate);
    }

    private PortfolioSummaryResponse response(String summary) {
        PortfolioSummaryResponse response = new PortfolioSummaryResponse();
        response.setPdfId("a".repeat(64));
        response.setResponse(summary);
        return response;
    }

    @SuppressWarnings("unchecked")
    private MultiValueMap<String, Object> capturedParts() {
        ArgumentCaptor<MultiValueMap<String, Object>> parts = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(aiCallTemplate).postMultipart(eq("/portfolios/summarize"), parts.capture(), any());
        return parts.getValue();
    }

    @Test
    @DisplayName("파트 이름은 AI 명세가 정한 pdf_file 이고, 파일명이 실려야 한다 (없으면 FastAPI 가 422)")
    void sendsPdfFilePartWithFilename() {
        when(aiCallTemplate.postMultipart(any(), any(), any())).thenReturn(response("요약"));

        client.summarize(PDF_BYTES, "portfolio.pdf");

        MultiValueMap<String, Object> parts = capturedParts();
        assertThat(parts.keySet()).containsExactly("pdf_file");

        HttpEntity<?> filePart = (HttpEntity<?>) parts.getFirst("pdf_file");
        // ByteArrayResource 는 기본적으로 파일명이 없다. 이 단정이 깨지면 멀티파트에 filename 이
        // 빠지고 FastAPI 가 일반 폼 필드로 취급해 422 를 낸다.
        assertThat(((Resource) filePart.getBody()).getFilename()).isEqualTo("portfolio.pdf");
        assertThat(filePart.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    }

    @Test
    @DisplayName("요약이 비어 있으면 502 다 (보여 줄 것도, 캐시에 넣을 것도 없다)")
    void rejectsEmptySummary() {
        when(aiCallTemplate.postMultipart(any(), any(), any())).thenReturn(response("   "));

        assertThatThrownBy(() -> client.summarize(PDF_BYTES, "portfolio.pdf"))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.AI_SERVER_ERROR);
    }

    @Test
    @DisplayName("요약이 null 이어도 502 다")
    void rejectsNullSummary() {
        when(aiCallTemplate.postMultipart(any(), any(), any())).thenReturn(response(null));

        assertThatThrownBy(() -> client.summarize(PDF_BYTES, "portfolio.pdf"))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.AI_SERVER_ERROR);
    }
}

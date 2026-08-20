package com.example.mateon.portfolios.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * FastAPI POST /portfolios/summarize 응답 본문.
 *
 * <p>
 * @JsonNaming(SnakeCaseStrategy) 을 쓰지 않는 이유는 {@code ContestExtractResponse} 주석과 같다 —
 * Jackson 2 와 3 이 동시에 클래스패스에 있어(Boot 4 는 Jackson 3, jjwt-jackson 이 Jackson 2)
 * 어느 컨버터가 잡히느냐에 따라 조용히 무시되고 전 필드가 null 이 된다. @JsonProperty 는 양쪽에서
 * 동작한다. @JsonIgnoreProperties 도 마찬가지로 클래스에 직접 명시해야 한다 —
 * MateonBackendApplication 의 FAIL_ON_UNKNOWN_PROPERTIES 설정은 aiRestTemplate 의
 * 자체 컨버터에는 걸리지 않는다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class PortfolioSummaryResponse {

    /**
     * PDF 원본 바이트의 SHA-256 (소문자 hex 64자). AI 가 채번한 값이 아니라 계산한 값이라,
     * 우리도 같은 바이트에서 같은 값을 얻는다 — 그래서 캐시 키로 쓸 수 있다.
     */
    @JsonProperty("pdf_id")
    private String pdfId;

    /**
     * 불릿 목록 + "요약" 문단으로 이어지는 마크다운 문자열 하나. 고정 스키마가 아니다.
     * 백엔드는 해석하지 않고 그대로 저장·전달한다.
     */
    @JsonProperty("response")
    private String response;
}

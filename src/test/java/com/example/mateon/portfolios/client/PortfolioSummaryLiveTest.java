package com.example.mateon.portfolios.client;

import com.example.mateon.support.AiStubSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포트폴리오 요약 응답이 ai-stub 에서 실제로 돌아와 DTO 로 채워지는지 확인한다.
 *
 * <p>
 * {@link PortfolioSummaryClientTest} 는 {@code AiCallTemplate} 을 목으로 두므로 응답 JSON 이
 * 개입하지 않는다. 즉 {@code @JsonProperty("pdf_id")} 는 지금까지 한 번도 실행된 적이 없다.
 * 깨지면 {@code pdfId} 가 null 이 되는데, 백엔드는 자기가 계산한 SHA-256 을 캐시 키로 쓰므로
 * (AI 값에 의존하지 않는다) <b>기능은 그대로 돌아간다</b> — 진단 로그만 조용히 쓸모없어진다.
 *
 * <p>
 * PDF 는 코드로 만든 최소 바이트다. 스텁은 파트 본문의 {@code %PDF} 접두어만 보고 없어도
 * 경고에 그친다. <b>실서버라면 이 바이트로는 부족하다</b> — 진짜 PDF 구조가 아니라
 * 요약이 나오지 않는다. 그래서 이 테스트는 스텁 전용이고, 실물 샘플을 레포에 커밋하지
 * 않는 대가로 "AI 가 내용을 읽어낼 수 있는가"는 검증 범위에서 빠진다.
 */
class PortfolioSummaryLiveTest {

    /** 스텁이 확인하는 건 %PDF 접두어뿐이다. 내용 자체에는 의미가 없다. */
    private static final byte[] PDF_BYTES
      = "%PDF-1.4\n포트폴리오 본문 (스텁용 최소 바이트)\n".getBytes(StandardCharsets.UTF_8);

    private PortfolioSummaryClient client;

    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        client = new PortfolioSummaryClient(AiStubSupport.aiCallTemplate());
    }

    @Test
    @DisplayName("pdf_id 와 response 가 채워진다")
    void responseIsMapped() {
        PortfolioSummaryResponse response = client.summarize(PDF_BYTES, "portfolio.pdf");

        assertThat(response.getPdfId()).isNotBlank();
        assertThat(response.getResponse()).isNotBlank();
    }

    /**
     * 실서버는 PDF 바이트의 SHA-256 을 준다(소문자 hex 64자). 스텁은 고정 더미값이지만
     * 길이는 같게 맞춰 놨다. 길이를 보는 이유는 {@code @JsonProperty} 가 어긋났을 때
     * null 이 아니라 <b>엉뚱한 필드 값</b>이 들어오는 경우를 배제하기 위해서다.
     */
    @Test
    @DisplayName("pdf_id 는 SHA-256 모양이다 (hex 64자)")
    void pdfIdLooksLikeSha256() {
        PortfolioSummaryResponse response = client.summarize(PDF_BYTES, "portfolio.pdf");

        assertThat(response.getPdfId()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("한글 파일명으로 보내도 요약이 돌아온다")
    void koreanFilenameWorks() {
        PortfolioSummaryResponse response = client.summarize(PDF_BYTES, "포트폴리오.pdf");

        assertThat(response.getResponse()).isNotBlank();
    }
}

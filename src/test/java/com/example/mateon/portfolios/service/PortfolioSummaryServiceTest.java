package com.example.mateon.portfolios.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.portfolios.client.PortfolioSummaryClient;
import com.example.mateon.portfolios.client.PortfolioSummaryResponse;
import com.example.mateon.portfolios.domain.UserPortfolio;
import com.example.mateon.portfolios.dto.PortfolioSummaryResponseDTO;
import com.example.mateon.portfolios.repository.UserPortfolioRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 포트폴리오 PDF 요약의 규약을 고정한다.
 *
 * <p>
 * 핵심은 <b>캐시 히트면 AI 를 부르지 않는다</b>는 것이다. 이 기능이 캐시를 두는 유일한 이유가
 * 15페이지 Vision 호출을 아끼는 것이라, 그 단정이 깨지면 테이블만 남고 목적은 사라진다.
 *
 * <p>
 * 그 다음이 검증 순서다 — 형식이 어긋난 파일은 AI 호출 전에 걸러져야 한다. 그러지 않으면
 * 잘못된 요청 하나하나가 LLM 비용이 된다(포스터 이미지 추출과 같은 원칙).
 */
class PortfolioSummaryServiceTest {

    private static final long USER_ID = 7L;
    private static final String SUMMARY = "- OO 서비스 프론트엔드 개발\n\n요약\n프론트엔드 중심의 경험을 쌓아왔습니다.";

    /**
     * 실제 PDF 처럼 %PDF 시그니처로 시작하는 바이트. 서비스가 이 앞부분을 본다.
     */
    private static final byte[] PDF_BYTES = "%PDF-1.7\n실제 포트폴리오 내용".getBytes(StandardCharsets.UTF_8);

    private PortfolioSummaryClient summaryClient;
    private UserPortfolioRepository portfolioRepository;
    private UserRepository userRepository;
    private PortfolioSummaryService service;

    @BeforeEach
    void setUp() {
        summaryClient = mock(PortfolioSummaryClient.class);
        portfolioRepository = mock(UserPortfolioRepository.class);
        userRepository = mock(UserRepository.class);
        service = new PortfolioSummaryService(summaryClient, portfolioRepository, userRepository);

        when(userRepository.findById(anyLong()))
          .thenReturn(Optional.of(User.builder().id(USER_ID).name("테스트 유저").build()));
        // 기본은 캐시 미스. 히트를 보는 테스트만 따로 덮어쓴다.
        when(portfolioRepository.findByUserIdAndPdfId(anyLong(), anyString())).thenReturn(Optional.empty());
    }

    private MultipartFile pdf(String filename) {
        return new MockMultipartFile("pdf_file", filename, "application/pdf", PDF_BYTES);
    }

    private PortfolioSummaryResponse aiResponse(String pdfId, String summary) {
        PortfolioSummaryResponse response = new PortfolioSummaryResponse();
        response.setPdfId(pdfId);
        response.setResponse(summary);
        return response;
    }

    /**
     * 서비스가 계산할 것과 같은 규약(SHA-256 소문자 hex)으로 기대값을 만든다.
     */
    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    @DisplayName("AI 가 준 마크다운을 그대로 돌려준다 (백엔드가 가공하지 않는다)")
    void returnsAiSummaryVerbatim() throws Exception {
        when(summaryClient.summarize(any(), anyString()))
          .thenReturn(aiResponse(sha256Hex(PDF_BYTES), SUMMARY));

        PortfolioSummaryResponseDTO result = service.summarize(USER_ID, pdf("portfolio.pdf"));

        assertThat(result.getSummary()).isEqualTo(SUMMARY);
    }

    @Test
    @DisplayName("캐시 키는 PDF 바이트의 SHA-256 이다 (AI 가 pdf_id 로 쓰는 값과 같은 규약)")
    void cacheKeyIsSha256OfPdfBytes() throws Exception {
        when(summaryClient.summarize(any(), anyString()))
          .thenReturn(aiResponse(sha256Hex(PDF_BYTES), SUMMARY));

        service.summarize(USER_ID, pdf("portfolio.pdf"));

        String expected = sha256Hex(PDF_BYTES);
        verify(portfolioRepository).findByUserIdAndPdfId(USER_ID, expected);

        ArgumentCaptor<UserPortfolio> saved = ArgumentCaptor.forClass(UserPortfolio.class);
        verify(portfolioRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPdfId()).isEqualTo(expected);
        assertThat(saved.getValue().getSummary()).isEqualTo(SUMMARY);
        assertThat(saved.getValue().getFilename()).isEqualTo("portfolio.pdf");
    }

    @Test
    @DisplayName("이미 요약한 PDF 면 AI 를 부르지 않고 저장된 요약을 돌려준다 (이 기능의 존재 이유)")
    void cacheHitSkipsAiCall() throws Exception {
        UserPortfolio cached = new UserPortfolio(null, sha256Hex(PDF_BYTES), "저장돼 있던 요약", "old.pdf");
        when(portfolioRepository.findByUserIdAndPdfId(eq(USER_ID), anyString()))
          .thenReturn(Optional.of(cached));

        PortfolioSummaryResponseDTO result = service.summarize(USER_ID, pdf("portfolio.pdf"));

        assertThat(result.getSummary()).isEqualTo("저장돼 있던 요약");
        verifyNoInteractions(summaryClient);
        // 캐시를 다시 쓰지도 않는다 — 같은 파일이라 덮어쓸 내용이 없다.
        verify(portfolioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("pdf 가 아닌 확장자는 AI 를 부르기 전에 400 으로 막는다")
    void rejectsNonPdfExtension() {
        assertThatThrownBy(() -> service.summarize(USER_ID, pdf("portfolio.docx")))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_PDF_FILE);

        verifyNoInteractions(summaryClient, portfolioRepository);
    }

    @Test
    @DisplayName("확장자가 아예 없어도 400")
    void rejectsMissingExtension() {
        assertThatThrownBy(() -> service.summarize(USER_ID, pdf("portfolio")))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_PDF_FILE);

        verifyNoInteractions(summaryClient);
    }

    @Test
    @DisplayName("확장자 대소문자는 가리지 않는다 (.PDF 도 통과)")
    void acceptsUppercaseExtension() throws Exception {
        when(summaryClient.summarize(any(), anyString()))
          .thenReturn(aiResponse(sha256Hex(PDF_BYTES), SUMMARY));

        assertThat(service.summarize(USER_ID, pdf("PORTFOLIO.PDF")).getSummary()).isEqualTo(SUMMARY);
    }

    @Test
    @DisplayName("확장자만 .pdf 인 파일은 시그니처 검사에서 걸린다 (AI 왕복 비용을 아낀다)")
    void rejectsFileWithoutPdfSignature() {
        MultipartFile fake = new MockMultipartFile(
          "pdf_file", "portfolio.pdf", "application/pdf",
          "이건 그냥 텍스트다".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.summarize(USER_ID, fake))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_PDF_FILE);

        verifyNoInteractions(summaryClient);
    }

    @Test
    @DisplayName("파일이 null 이면 400")
    void rejectsNullFile() {
        assertThatThrownBy(() -> service.summarize(USER_ID, null))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_PDF_FILE);

        verifyNoInteractions(summaryClient, portfolioRepository);
    }

    @Test
    @DisplayName("파일명이 비어 있으면 400")
    void rejectsBlankFilename() {
        MultipartFile file = new MockMultipartFile(
          "pdf_file", "", "application/pdf", PDF_BYTES);

        assertThatThrownBy(() -> service.summarize(USER_ID, file))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_PDF_FILE);

        verifyNoInteractions(summaryClient);
    }

    @Test
    @DisplayName("시그니처보다 짧은 파일은 400")
    void rejectsShorterThanPdfMagic() {
        MultipartFile file = new MockMultipartFile(
          "pdf_file", "portfolio.pdf", "application/pdf",
          "%PD".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> service.summarize(USER_ID, file))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_PDF_FILE);

        verifyNoInteractions(summaryClient);
    }

    @Test
    @DisplayName("바이트를 읽다 실패하면 400")
    void rejectsUnreadableFile() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn((long) PDF_BYTES.length);
        when(file.getOriginalFilename()).thenReturn("portfolio.pdf");
        when(file.getBytes()).thenThrow(new IOException("디스크 오류"));

        assertThatThrownBy(() -> service.summarize(USER_ID, file))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_PDF_FILE);

        verifyNoInteractions(summaryClient);
    }

    @Test
    @DisplayName("빈 파일은 400")
    void rejectsEmptyFile() {
        MultipartFile empty = new MockMultipartFile(
          "pdf_file", "portfolio.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.summarize(USER_ID, empty))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.INVALID_PDF_FILE);

        verifyNoInteractions(summaryClient);
    }

    @Test
    @DisplayName("20MB 를 넘으면 413")
    void rejectsOversizedFile() {
        MultipartFile huge = new MockMultipartFile(
          "pdf_file", "portfolio.pdf", "application/pdf", new byte[20 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> service.summarize(USER_ID, huge))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.PDF_TOO_LARGE);

        verifyNoInteractions(summaryClient);
    }

    @Test
    @DisplayName("AI 가 다른 pdf_id 를 보내도 실패시키지 않고, 캐시 키로는 우리 해시를 쓴다")
    void keepsOwnHashWhenAiDisagrees() throws Exception {
        when(summaryClient.summarize(any(), anyString()))
          .thenReturn(aiResponse("전혀 다른 값", SUMMARY));

        PortfolioSummaryResponseDTO result = service.summarize(USER_ID, pdf("portfolio.pdf"));

        // 요약 자체는 멀쩡하므로 사용자에게는 그대로 나간다.
        assertThat(result.getSummary()).isEqualTo(SUMMARY);

        // 캐시 키는 다음 요청에서도 우리가 계산할 수 있는 값이어야 조회가 성립한다.
        ArgumentCaptor<UserPortfolio> saved = ArgumentCaptor.forClass(UserPortfolio.class);
        verify(portfolioRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPdfId()).isEqualTo(sha256Hex(PDF_BYTES));
    }

    @Test
    @DisplayName("AI 호출이 실패하면 아무것도 저장하지 않는다")
    void doesNotSaveWhenAiFails() {
        doThrow(new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE))
          .when(summaryClient).summarize(any(), anyString());

        assertThatThrownBy(() -> service.summarize(USER_ID, pdf("portfolio.pdf")))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.AI_SERVER_UNAVAILABLE);

        verify(portfolioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동시에 같은 PDF 를 올려 UNIQUE 제약에 걸려도 요청은 성공한다 (요약은 이미 손에 있다)")
    void treatsUniqueViolationAsSuccess() throws Exception {
        when(summaryClient.summarize(any(), anyString()))
          .thenReturn(aiResponse(sha256Hex(PDF_BYTES), SUMMARY));
        doThrow(new DataIntegrityViolationException("uq_user_portfolios_pair"))
          .when(portfolioRepository).saveAndFlush(any());

        assertThat(service.summarize(USER_ID, pdf("portfolio.pdf")).getSummary()).isEqualTo(SUMMARY);
    }

    @Test
    @DisplayName("파일명이 컬럼 길이를 넘으면 잘라 저장한다 (INSERT 실패로 캐시가 비면 안 된다)")
    void truncatesOverlongFilename() throws Exception {
        when(summaryClient.summarize(any(), anyString()))
          .thenReturn(aiResponse(sha256Hex(PDF_BYTES), SUMMARY));
        String longName = "가".repeat(300) + ".pdf";

        service.summarize(USER_ID, pdf(longName));

        ArgumentCaptor<UserPortfolio> saved = ArgumentCaptor.forClass(UserPortfolio.class);
        verify(portfolioRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFilename()).hasSize(255);
    }

    @Test
    @DisplayName("파일명이 null 이면 자르지 않고 그대로 둔다")
    void truncateFilenameKeepsNull() {
        String truncated = ReflectionTestUtils.invokeMethod(
          service, "truncateFilename", new Object[]{null});

        assertThat(truncated).isNull();
    }
}

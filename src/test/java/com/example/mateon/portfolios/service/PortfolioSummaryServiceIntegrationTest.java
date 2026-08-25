package com.example.mateon.portfolios.service;

import com.example.mateon.portfolios.client.PortfolioSummaryClient;
import com.example.mateon.portfolios.client.PortfolioSummaryResponse;
import com.example.mateon.portfolios.domain.UserPortfolio;
import com.example.mateon.portfolios.repository.UserPortfolioRepository;
import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 캐시가 실제 DB(uq_user_portfolios_pair)에 대고 동작하는지 확인한다.
 *
 * <p>
 * 목 리포지토리로는 확인할 수 없는 것들이다 — 제약이 실행되지 않고, 저장한 행이 다음 조회에
 * 실제로 잡히는지도 알 수 없다.
 *
 * <p>
 * AI 클라이언트만 목이다. 스프링 컨텍스트에 목을 끼우는 대신(그러면 컨텍스트가 하나 더 뜬다)
 * 오토와이어한 리포지토리로 서비스를 직접 조립한다 — 생성자 주입이라 그대로 된다.
 *
 * <p>
 * 목은 호출할 때마다 <b>다른 요약</b>을 준다. 같은 값을 주면 캐시가 동작하지 않아도 테스트가
 * 통과해 버려서, "두 번째 응답이 첫 번째와 같다"가 캐시의 증거가 되지 못한다.
 */
class PortfolioSummaryServiceIntegrationTest extends IntegrationTestBase {

    private static final byte[] PDF_BYTES
      = "%PDF-1.7\n포트폴리오 본문".getBytes(StandardCharsets.UTF_8);

    @Autowired
    UserPortfolioRepository portfolioRepository;
    @Autowired
    UserRepository userRepository;

    private PortfolioSummaryClient summaryClient;
    private PortfolioSummaryService service;
    private Long userId;

    @BeforeEach
    void setUp() {
        summaryClient = mock(PortfolioSummaryClient.class);
        service = new PortfolioSummaryService(summaryClient, portfolioRepository, userRepository);

        AtomicInteger callCount = new AtomicInteger();
        when(summaryClient.summarize(any(), anyString())).thenAnswer(invocation -> {
            PortfolioSummaryResponse response = new PortfolioSummaryResponse();
            response.setResponse("요약 #" + callCount.incrementAndGet());
            return response;
        });

        userId = newUser();
    }

    private Long newUser() {
        return userRepository.save(User.builder()
          .email(UUID.randomUUID() + "@test.ac.kr")
          .name("포트폴리오 테스트 유저")
          .build()).getId();
    }

    private MultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("pdf_file", "portfolio.pdf", "application/pdf", bytes);
    }

    private List<UserPortfolio> myPortfolios(Long ownerId) {
        return portfolioRepository.findAll().stream()
          .filter(portfolio -> portfolio.getUser().getId().equals(ownerId))
          .toList();
    }

    @Test
    @DisplayName("같은 PDF 를 두 번 올리면 AI 는 한 번만 부르고 행도 하나만 남는다")
    void secondUploadOfSameFileIsServedFromCache() {
        String first = service.summarize(userId, pdf(PDF_BYTES)).getSummary();
        String second = service.summarize(userId, pdf(PDF_BYTES)).getSummary();

        assertThat(second).isEqualTo(first);
        verify(summaryClient, times(1)).summarize(any(), anyString());
        assertThat(myPortfolios(userId)).hasSize(1);
    }

    @Test
    @DisplayName("내용이 다른 PDF 는 다른 해시라 각각 요약된다")
    void differentContentIsSummarizedSeparately() {
        service.summarize(userId, pdf(PDF_BYTES));
        service.summarize(userId, pdf("%PDF-1.7\n전혀 다른 포트폴리오".getBytes(StandardCharsets.UTF_8)));

        verify(summaryClient, times(2)).summarize(any(), anyString());
        assertThat(myPortfolios(userId)).hasSize(2);
    }

    @Test
    @DisplayName("다른 사람이 같은 PDF 를 올리면 남의 요약을 재사용하지 않는다 (캐시는 유저별이다)")
    void cacheIsScopedPerUser() {
        Long otherUserId = newUser();

        String mine = service.summarize(userId, pdf(PDF_BYTES)).getSummary();
        String theirs = service.summarize(otherUserId, pdf(PDF_BYTES)).getSummary();

        // 목이 호출마다 다른 값을 주므로, 값이 다르다는 건 AI 를 새로 불렀다는 뜻이다.
        assertThat(theirs).isNotEqualTo(mine);
        verify(summaryClient, times(2)).summarize(any(), anyString());
        assertThat(myPortfolios(userId)).hasSize(1);
        assertThat(myPortfolios(otherUserId)).hasSize(1);
    }

    @Test
    @DisplayName("저장된 pdf_id 는 PDF 바이트의 SHA-256 소문자 hex 64자다")
    void storesSha256AsPdfId() {
        service.summarize(userId, pdf(PDF_BYTES));

        assertThat(myPortfolios(userId))
          .singleElement()
          .satisfies(portfolio -> assertThat(portfolio.getPdfId()).matches("[0-9a-f]{64}"));
    }
}

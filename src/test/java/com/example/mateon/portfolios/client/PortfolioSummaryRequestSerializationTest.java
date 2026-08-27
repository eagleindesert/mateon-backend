package com.example.mateon.portfolios.client;

import com.example.mateon.common.ai.AiCallTemplate;
import com.example.mateon.common.ai.AiServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 포트폴리오 요약 요청이 실제 와이어에 어떤 바이트로 나가는지 고정한다.
 *
 * <p>
 * 이 엔드포인트는 JSON 이 아니라 multipart/form-data 라, 지켜야 할 계약이 키 이름이 아니라
 * <b>파트 헤더</b>에 있다. FastAPI 가 파트를 UploadFile 로 인식하려면 Content-Disposition 에
 * {@code filename} 파라미터가 있어야 하고, 없으면 일반 폼 필드로 취급해 422 를 낸다.
 *
 * <p>
 * 그래서 파트 맵을 캡처해 들여다보는 방식({@link PortfolioSummaryClientTest})으로는 부족하다.
 * {@code filename} 은 맵에 담긴 {@code Resource} 의 {@code getFilename()} 이 <b>직렬화될 때</b>
 * 헤더로 바뀌는 값이라, 실제로 본문에 실렸는지는 원문 바이트를 봐야 알 수 있다.
 * 이 클래스는 {@link MockClientHttpRequest#getBodyAsString()} 으로 그 원문을 읽는다.
 */
class PortfolioSummaryRequestSerializationTest {

    private static final String BASE_URL = "http://ai.test:8001";
    private static final String SUMMARIZE_URL = BASE_URL + "/portfolios/summarize";

    /** 바이너리가 아니라 ASCII 로 둔다 — 원문을 문자열로 읽어 확인하기 위해서다. */
    private static final byte[] PDF_BYTES = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);

    private static final String SUMMARY_JSON = """
      {"pdf_id": "abc123", "response": "요약입니다."}
      """;

    private MockRestServiceServer server;
    private PortfolioSummaryClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setInternalSecret("test-secret");

        client = new PortfolioSummaryClient(new AiCallTemplate(restTemplate, properties));
    }

    @Test
    @DisplayName("파트 이름은 pdf_file 이고 filename 이 실제 본문에 실린다 (없으면 FastAPI 가 422)")
    void filePartCarriesNameAndFilename() {
        server.expect(requestTo(SUMMARIZE_URL))
          .andExpect(request -> {
              String body = ((MockClientHttpRequest) request).getBodyAsString();

              assertThat(body).contains("name=\"pdf_file\"");
              assertThat(body).contains("filename=\"portfolio.pdf\"");
              assertThat(body).contains("Content-Type: application/pdf");
              assertThat(body).contains("%PDF-1.4 fake");
          })
          .andRespond(withSuccess(SUMMARY_JSON, MediaType.APPLICATION_JSON));

        client.summarize(PDF_BYTES, "portfolio.pdf");

        server.verify();
    }

    @Test
    @DisplayName("보내는 파트는 하나뿐이다 (경계 문자열이 두 번 열리지 않는다)")
    void sendsExactlyOnePart() {
        server.expect(requestTo(SUMMARIZE_URL))
          .andExpect(request -> {
              String body = ((MockClientHttpRequest) request).getBodyAsString();

              assertThat(countOccurrences(body, "Content-Disposition: form-data")).isEqualTo(1);
          })
          .andRespond(withSuccess(SUMMARY_JSON, MediaType.APPLICATION_JSON));

        client.summarize(PDF_BYTES, "portfolio.pdf");

        server.verify();
    }

    @Test
    @DisplayName("한글 파일명도 파트 헤더에 살아남는다")
    void keepsKoreanFilename() {
        server.expect(requestTo(SUMMARIZE_URL))
          .andExpect(request -> {
              String body = ((MockClientHttpRequest) request).getBodyAsString();

              assertThat(body).contains("name=\"pdf_file\"");
              assertThat(body).contains("포트폴리오.pdf");
          })
          .andRespond(withSuccess(SUMMARY_JSON, MediaType.APPLICATION_JSON));

        client.summarize(PDF_BYTES, "포트폴리오.pdf");

        server.verify();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}

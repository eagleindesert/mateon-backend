package com.example.mateon.events.client;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 포스터 이미지 추출 요청이 실제 와이어에 어떤 바이트로 나가는지 고정한다.
 *
 * <p>
 * 파트 이름이 <b>{@code img_file}</b> 이다 — 포트폴리오 요약의 {@code pdf_file} 과 다르다.
 * 두 엔드포인트가 같은 {@code postMultipart} 를 거치고 코드 모양도 거의 같아서, 한쪽을 보고
 * 다른 쪽을 고칠 때 파트 이름까지 따라 붙여 넣기 쉽다. 그러면 FastAPI 가 422 를 낸다.
 *
 * <p>
 * 원문 바이트를 읽어 확인하는 이유는
 * {@link com.example.mateon.portfolios.client.PortfolioSummaryRequestSerializationTest}
 * 클래스 주석과 같다 — Content-Disposition 의 {@code filename} 은 직렬화 시점에 생기는
 * 값이라 파트 맵만 봐서는 실렸는지 알 수 없다.
 */
class ContestImageExtractionClientTest {

    private static final String BASE_URL = "http://ai.test:8001";
    private static final String EXTRACT_URL = BASE_URL + "/contests/extract-image";
    private static final String SECRET = "test-internal-secret";

    /** 바이너리가 아니라 ASCII 로 둔다 — 원문을 문자열로 읽어 확인하기 위해서다. */
    private static final byte[] IMAGE_BYTES = "PNGfake".getBytes(StandardCharsets.UTF_8);

    private static final String EXTRACTED_JSON = """
      {"title": "2026 커머스 아이디어 공모전", "organizer": "메이트온"}
      """;

    private MockRestServiceServer server;
    private ContestImageExtractionClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setInternalSecret(SECRET);

        client = new ContestImageExtractionClient(new AiCallTemplate(restTemplate, properties));
    }

    @Test
    @DisplayName("파트 이름은 img_file 이고 filename 이 실제 본문에 실린다 (pdf_file 이 아니다)")
    void filePartCarriesNameAndFilename() {
        server.expect(requestTo(EXTRACT_URL))
          .andExpect(method(org.springframework.http.HttpMethod.POST))
          .andExpect(request -> {
              String body = ((MockClientHttpRequest) request).getBodyAsString();

              assertThat(body).contains("name=\"img_file\"");
              assertThat(body).doesNotContain("name=\"pdf_file\"");
              assertThat(body).contains("filename=\"poster.png\"");
              assertThat(body).contains("PNGfake");
          })
          .andRespond(withSuccess(EXTRACTED_JSON, MediaType.APPLICATION_JSON));

        client.extract(IMAGE_BYTES, "poster.png", "image/png");

        server.verify();
    }

    @Test
    @DisplayName("호출자가 준 콘텐츠 타입이 파트 헤더로 나간다 (png 를 jpeg 라고 말하지 않는다)")
    void partCarriesGivenContentType() {
        server.expect(requestTo(EXTRACT_URL))
          .andExpect(request -> {
              String body = ((MockClientHttpRequest) request).getBodyAsString();

              assertThat(body).contains("Content-Type: image/jpeg");
          })
          .andRespond(withSuccess(EXTRACTED_JSON, MediaType.APPLICATION_JSON));

        client.extract(IMAGE_BYTES, "poster.jpg", "image/jpeg");

        server.verify();
    }

    @Test
    @DisplayName("multipart/form-data 로 보내고 X-Internal-Secret 을 붙인다")
    void sendsMultipartWithInternalSecret() {
        server.expect(requestTo(EXTRACT_URL))
          .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith(
            MediaType.MULTIPART_FORM_DATA_VALUE)))
          .andExpect(header("X-Internal-Secret", SECRET))
          .andRespond(withSuccess(EXTRACTED_JSON, MediaType.APPLICATION_JSON));

        client.extract(IMAGE_BYTES, "poster.png", "image/png");

        server.verify();
    }

    @Test
    @DisplayName("응답의 snake_case 키가 매핑된다")
    void mapsSnakeCaseResponse() {
        server.expect(requestTo(EXTRACT_URL))
          .andRespond(withSuccess("""
            {
              "external_id": "ext-1",
              "target_school": "전체",
              "start_date": "2026-09-01",
              "title": "2026 커머스 아이디어 공모전"
            }
            """, MediaType.APPLICATION_JSON));

        ContestExtractResponse response = client.extract(IMAGE_BYTES, "poster.png", "image/png");

        assertThat(response.getExternalId()).isEqualTo("ext-1");
        assertThat(response.getTargetSchool()).isEqualTo("전체");
        assertThat(response.getStartDate()).isEqualTo("2026-09-01");
        assertThat(response.getTitle()).isEqualTo("2026 커머스 아이디어 공모전");
    }
}

package com.example.mateon.events.client;

import com.example.mateon.support.AiStubSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포스터 이미지 추출 응답이 ai-stub 에서 실제로 돌아와 DTO 로 채워지는지 확인한다.
 *
 * <p>
 * {@link ContestExtractResponse} 는 필드 13개가 전부 {@code @JsonProperty} 로 매핑되는데,
 * 그중 어느 하나가 어긋나도 예외가 아니라 null 이다. 그리고 이 DTO 는 애초에
 * <b>null 을 정상으로 취급한다</b> — LLM 이 이미지에서 못 읽어낸 항목은 비워 두는 게
 * 설계라(DTO 주석 참고), "그 필드만 비었다"가 버그로 보이지 않는다.
 * 그래서 매핑이 깨진 것과 AI 가 못 읽은 것을 구분할 수 있는 자리가 여기뿐이다.
 *
 * <p>
 * 이미지는 코드로 만든 PNG 시그니처 8바이트다. 스텁은 파트 이름과 filename 만 보고
 * 이미지 내용은 열지 않는다. 실서버라면 당연히 이 바이트로는 아무것도 못 읽는다 —
 * {@code PortfolioSummaryLiveTest} 클래스 주석과 같은 한계다.
 */
class ContestImageExtractionLiveTest {

    /** PNG 시그니처 8바이트. 스텁은 여기까지도 안 본다. */
    private static final byte[] PNG_BYTES = {
      (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };

    private ContestImageExtractionClient client;

    @BeforeAll
    static void requireStub() {
        AiStubSupport.assumeStubAvailable();
    }

    @BeforeEach
    void setUp() {
        client = new ContestImageExtractionClient(AiStubSupport.aiCallTemplate());
    }

    @Test
    @DisplayName("스텁이 채워 주는 필드가 전부 매핑된다")
    void populatedFieldsAreMapped() {
        ContestExtractResponse response = extract();

        assertThat(response.getTitle()).isNotBlank();
        assertThat(response.getOrganizer()).isNotBlank();
        assertThat(response.getCategory()).isNotBlank();
        assertThat(response.getField()).isNotBlank();
        assertThat(response.getStartDate()).isNotBlank();
        assertThat(response.getEndDate()).isNotBlank();
        assertThat(response.getDescription()).isNotBlank();
        assertThat(response.getSummarizedDescription()).isNotBlank();
        assertThat(response.getRecommendedTargets()).isNotBlank();
    }

    /**
     * 스텁은 이 셋을 일부러 null 로 준다 — 백엔드가 자기 버킷 URL 로 채우는지, 못 읽은
     * 항목을 비워 두는지 확인할 수 있게 하기 위해서다. null 을 그대로 받아야 정상이다.
     */
    @Test
    @DisplayName("AI 가 비워 보낸 필드는 null 로 온다 (빈 문자열로 채우지 않는다)")
    void absentFieldsStayNull() {
        ContestExtractResponse response = extract();

        assertThat(response.getExternalId()).isNull();
        assertThat(response.getImageUrl()).isNull();
        assertThat(response.getDetailUrl()).isNull();
    }

    /**
     * 날짜를 {@code LocalDate} 가 아니라 String 으로 받는 게 의도다 — 형식이 어긋나도
     * 역직렬화 단계에서 요청 전체가 죽지 않게 하려는 것이다(DTO 주석 참고).
     * 그 전제가 성립하려면 실제로 문자열 그대로 도착해야 한다.
     */
    @Test
    @DisplayName("날짜는 yyyy-MM-dd 문자열 그대로 온다 (여기서 타입 변환하지 않는다)")
    void datesArriveAsRawStrings() {
        ContestExtractResponse response = extract();

        assertThat(response.getStartDate()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(response.getEndDate()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private ContestExtractResponse extract() {
        return client.extract(PNG_BYTES, "poster.png", "image/png");
    }
}

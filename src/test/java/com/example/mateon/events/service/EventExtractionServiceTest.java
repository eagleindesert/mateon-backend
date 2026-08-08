package com.example.mateon.events.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.storage.BucketCapacityGuard;
import com.example.mateon.common.storage.ObjectStorageService;
import com.example.mateon.events.client.ContestExtractResponse;
import com.example.mateon.events.client.ContestImageExtractionClient;
import com.example.mateon.events.dto.EventExtractionResponseDTO;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 포스터 이미지 → 등록 초안의 규약을 고정한다.
 *
 * <p>특히 두 가지가 중요하다. (1) 형식이 어긋난 파일은 AI 를 호출하기 전에 걸러진다 —
 * 그러지 않으면 매 요청이 LLM 비용으로 이어진다. (2) AI 추출이 실패하면 업로드를 하지 않는다 —
 * 초안을 못 받은 사용자는 다시 올릴 테니, 순서가 뒤집히면 버킷에 아무도 안 쓰는 이미지가 쌓인다.
 */
class EventExtractionServiceTest {

    private static final String UPLOADED_URL =
            "https://objectstorage.ap-chuncheon-1.oraclecloud.com/n/ns/b/bucket/o/contest-images/2026/07/x.png";

    private ContestImageExtractionClient extractionClient;
    private ObjectStorageService objectStorageService;
    private EventExtractionService service;

    @BeforeEach
    void setUp() {
        extractionClient = mock(ContestImageExtractionClient.class);
        objectStorageService = mock(ObjectStorageService.class);
        // 용량 가드는 기본 mock 이라 항상 통과한다. 한도 동작 자체는 BucketCapacityGuardTest 가 본다.
        service = new EventExtractionService(
                extractionClient, objectStorageService, mock(BucketCapacityGuard.class));
    }

    private MultipartFile image(String filename) {
        return new MockMultipartFile("image", filename, "image/png", new byte[]{1, 2, 3});
    }

    private ContestExtractResponse fullResponse() {
        ContestExtractResponse response = new ContestExtractResponse();
        response.setCategory("CONTEST");
        response.setField("PLANNING_IDEA");
        response.setTitle("2026 제10회 <051영화제> 51초 영화 공모전");
        response.setOrganizer("부산시사회복지협의회");
        response.setStartDate("2026-07-01");
        response.setEndDate("2026-07-31");
        response.setDescription("주제 '연결'");
        response.setSummarizedDescription("요약");
        response.setRecommendedTargets("대상 제한 없음");
        return response;
    }

    @Test
    @DisplayName("추출 결과와 업로드한 이미지 URL 을 합쳐 초안으로 돌려준다")
    void returnsDraftWithUploadedImageUrl() {
        when(extractionClient.extract(any(), anyString(), anyString())).thenReturn(fullResponse());
        when(objectStorageService.upload(anyString(), any(), anyString())).thenReturn(UPLOADED_URL);

        EventExtractionResponseDTO draft = service.extractFromImage(image("poster.png"));

        assertThat(draft.getCategory()).isEqualTo(Category.CONTEST);
        assertThat(draft.getField()).isEqualTo(Field.PLANNING_IDEA);
        assertThat(draft.getTitle()).isEqualTo("2026 제10회 <051영화제> 51초 영화 공모전");
        assertThat(draft.getStartDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(draft.getEndDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(draft.getImageUrl()).isEqualTo(UPLOADED_URL);
    }

    @Test
    @DisplayName("AI 가 읽어낸 image_url 이 아니라 우리가 올린 URL 을 준다")
    void ignoresAiImageUrl() {
        ContestExtractResponse response = fullResponse();
        response.setImageUrl("htp://오타섞인.주소/포스터.png"); // OCR 로 읽은 값이라 신뢰할 수 없다
        when(extractionClient.extract(any(), anyString(), anyString())).thenReturn(response);
        when(objectStorageService.upload(anyString(), any(), anyString())).thenReturn(UPLOADED_URL);

        assertThat(service.extractFromImage(image("poster.png")).getImageUrl()).isEqualTo(UPLOADED_URL);
    }

    @Test
    @DisplayName("객체 키는 연/월로 나뉘고 확장자를 유지한다")
    void buildsDatePartitionedKey() {
        when(extractionClient.extract(any(), anyString(), anyString())).thenReturn(fullResponse());
        when(objectStorageService.upload(anyString(), any(), anyString())).thenReturn(UPLOADED_URL);

        service.extractFromImage(image("포스터.JPG"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        // content-type 은 업로드 파일이 주장한 값이 아니라 확장자에서 정한다.
        verify(objectStorageService).upload(key.capture(), any(), eq("image/jpeg"));
        assertThat(key.getValue()).matches(
                "contest-images/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg");
    }

    @Test
    @DisplayName("허용하지 않는 확장자는 AI 를 부르기 전에 400 으로 막는다")
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> service.extractFromImage(image("poster.gif")))
                .isInstanceOf(MateonException.class)
                .extracting(e -> ((MateonException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_IMAGE_FILE);

        verifyNoInteractions(extractionClient, objectStorageService);
    }

    @Test
    @DisplayName("확장자가 아예 없어도 400")
    void rejectsMissingExtension() {
        assertThatThrownBy(() -> service.extractFromImage(image("poster")))
                .isInstanceOf(MateonException.class)
                .extracting(e -> ((MateonException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_IMAGE_FILE);
    }

    @Test
    @DisplayName("빈 파일은 400")
    void rejectsEmptyFile() {
        MultipartFile empty = new MockMultipartFile("image", "poster.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.extractFromImage(empty))
                .isInstanceOf(MateonException.class)
                .extracting(e -> ((MateonException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_IMAGE_FILE);

        verifyNoInteractions(extractionClient);
    }

    @Test
    @DisplayName("10MB 를 넘으면 413")
    void rejectsOversizedFile() {
        MultipartFile huge = new MockMultipartFile(
                "image", "poster.png", "image/png", new byte[10 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> service.extractFromImage(huge))
                .isInstanceOf(MateonException.class)
                .extracting(e -> ((MateonException) e).getErrorCode())
                .isEqualTo(ErrorCode.IMAGE_TOO_LARGE);

        verifyNoInteractions(extractionClient);
    }

    @Test
    @DisplayName("AI 추출이 실패하면 이미지를 올리지 않는다 (버킷에 고아 객체를 남기지 않는다)")
    void doesNotUploadWhenExtractionFails() {
        doThrow(new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE))
                .when(extractionClient).extract(any(), anyString(), anyString());

        assertThatThrownBy(() -> service.extractFromImage(image("poster.png")))
                .isInstanceOf(MateonException.class)
                .extracting(e -> ((MateonException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_SERVER_UNAVAILABLE);

        verify(objectStorageService, never()).upload(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("업로드가 실패하면 요청 전체가 실패한다 (이미지 없는 초안을 주지 않는다)")
    void failsWhenUploadFails() {
        when(extractionClient.extract(any(), anyString(), anyString())).thenReturn(fullResponse());
        doThrow(new MateonException(ErrorCode.IMAGE_UPLOAD_FAILED))
                .when(objectStorageService).upload(anyString(), any(), anyString());

        assertThatThrownBy(() -> service.extractFromImage(image("poster.png")))
                .isInstanceOf(MateonException.class)
                .extracting(e -> ((MateonException) e).getErrorCode())
                .isEqualTo(ErrorCode.IMAGE_UPLOAD_FAILED);
    }

    @Test
    @DisplayName("AI 가 모르는 코드값을 보내도 500 이 아니라 ETC 초안을 준다")
    void fallsBackToEtcForUnknownCodes() {
        ContestExtractResponse response = fullResponse();
        response.setCategory("공모전");        // enum 코드가 아니라 라벨
        response.setField("AI_ROBOTICS");    // 우리 목록에 없는 분야
        when(extractionClient.extract(any(), anyString(), anyString())).thenReturn(response);
        when(objectStorageService.upload(anyString(), any(), anyString())).thenReturn(UPLOADED_URL);

        EventExtractionResponseDTO draft = service.extractFromImage(image("poster.png"));

        assertThat(draft.getCategory()).isEqualTo(Category.ETC);
        assertThat(draft.getField()).isEqualTo(Field.ETC);
    }

    @Test
    @DisplayName("날짜를 못 읽었거나 형식이 깨졌으면 그 필드만 비운다")
    void leavesUnparsableDatesEmpty() {
        ContestExtractResponse response = fullResponse();
        response.setStartDate("2026년 7월 1일");
        response.setEndDate(null);
        when(extractionClient.extract(any(), anyString(), anyString())).thenReturn(response);
        when(objectStorageService.upload(anyString(), any(), anyString())).thenReturn(UPLOADED_URL);

        EventExtractionResponseDTO draft = service.extractFromImage(image("poster.png"));

        assertThat(draft.getStartDate()).isNull();
        assertThat(draft.getEndDate()).isNull();
        // 나머지 초안은 그대로 살아 있어야 한다 — 사용자가 날짜만 채우면 된다.
        assertThat(draft.getTitle()).isNotBlank();
    }
}

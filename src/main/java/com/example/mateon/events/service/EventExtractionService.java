package com.example.mateon.events.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.storage.ObjectStorageService;
import com.example.mateon.events.client.ContestExtractResponse;
import com.example.mateon.events.client.ContestImageExtractionClient;
import com.example.mateon.events.dto.EventExtractionResponseDTO;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 포스터 이미지 → 활동 등록 초안.
 *
 * <p>AI 추출과 객체 저장소 업로드를 묶기만 하고 DB 는 건드리지 않는다. 저장은 사용자가 초안을
 * 확인·수정한 뒤 POST /api/events 로 하는 별도 단계다 — 그래서 EventService 를 확장하지 않고
 * 별도 서비스로 뒀다(그쪽은 전부 리포지토리 작업이다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventExtractionService {

    /** AI 서버 제한(10MB)과 맞춘 값. 멀티파트 설정 뒤의 2차 방어선이다. */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    /** 확장자 → 저장소에 기록할 Content-Type. 브라우저가 보낸 값은 신뢰하지 않는다. */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png");

    private static final DateTimeFormatter KEY_PREFIX_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM");

    private final ContestImageExtractionClient extractionClient;
    private final ObjectStorageService objectStorageService;

    /**
     * @throws MateonException INVALID_IMAGE_FILE(400) / IMAGE_TOO_LARGE(413) — 업로드 파일 문제,
     *                         AI_SERVER_UNAVAILABLE(503) / AI_SERVER_ERROR(502) — AI 서버 문제,
     *                         IMAGE_UPLOAD_FAILED(502) — 객체 저장소 문제
     */
    public EventExtractionResponseDTO extractFromImage(MultipartFile image) {
        String extension = validateAndResolveExtension(image);
        String contentType = CONTENT_TYPES.get(extension);
        byte[] bytes = readBytes(image);

        // AI 추출을 먼저 한다. 순서를 뒤집으면 판독 실패한 이미지가 버킷에 그대로 남는다
        // (초안을 못 받은 사용자는 다시 올릴 테니 지울 사람도 없다).
        ContestExtractResponse extracted = extractionClient.extract(
                bytes, image.getOriginalFilename(), contentType);

        String key = "contest-images/%s/%s.%s".formatted(
                YearMonth.now().format(KEY_PREFIX_FORMAT), UUID.randomUUID(), extension);
        String imageUrl = objectStorageService.upload(key, bytes, contentType);

        return toDraft(extracted, imageUrl);
    }

    /** 확장자를 소문자로 돌려준다. 형식/크기 문제는 전부 여기서 걸러진다. */
    private String validateAndResolveExtension(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new MateonException(ErrorCode.INVALID_IMAGE_FILE);
        }
        if (image.getSize() > MAX_IMAGE_BYTES) {
            // 멀티파트 한도가 먼저 걸리는 게 정상이지만, 한도를 올려 잡은 환경에서도
            // AI 가 400 을 내기 전에 우리가 먼저 안내한다.
            throw new MateonException(ErrorCode.IMAGE_TOO_LARGE);
        }

        String filename = image.getOriginalFilename();
        if (!StringUtils.hasText(filename)) {
            throw new MateonException(ErrorCode.INVALID_IMAGE_FILE);
        }
        // 확장자를 1차 근거로 삼는다. content-type 은 클라이언트가 주는 값이라
        // 브라우저·OS 에 따라 application/octet-stream 으로도 오기 때문이다.
        String extension = StringUtils.getFilenameExtension(filename);
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT))) {
            throw new MateonException(ErrorCode.INVALID_IMAGE_FILE);
        }
        return extension.toLowerCase(Locale.ROOT);
    }

    /**
     * 바이트는 한 번만 읽는다. AI 전송과 업로드가 같은 배열을 쓴다 —
     * MultipartFile 의 스트림을 두 번 여는 방식은 임시 파일이 이미 정리된 뒤에 터질 수 있다.
     */
    private byte[] readBytes(MultipartFile image) {
        try {
            return image.getBytes();
        } catch (IOException e) {
            log.warn("업로드 파일을 읽지 못했습니다: {}", image.getOriginalFilename(), e);
            throw new MateonException(ErrorCode.INVALID_IMAGE_FILE);
        }
    }

    private EventExtractionResponseDTO toDraft(ContestExtractResponse extracted, String imageUrl) {
        return EventExtractionResponseDTO.builder()
                .category(parseEnum(Category.class, extracted.getCategory(), Category.ETC))
                .field(parseEnum(Field.class, extracted.getField(), Field.ETC))
                .title(extracted.getTitle())
                .organizer(extracted.getOrganizer())
                .targetSchool(extracted.getTargetSchool())
                .startDate(parseDate(extracted.getStartDate()))
                .endDate(parseDate(extracted.getEndDate()))
                .detailUrl(extracted.getDetailUrl())
                // AI 의 image_url 은 이미지 안에서 읽어낸 문자열이라 신뢰할 수 없다. 우리가 올린 URL 로 덮는다.
                .imageUrl(imageUrl)
                .description(extracted.getDescription())
                .summarizedDescription(extracted.getSummarizedDescription())
                .recommendedTargets(extracted.getRecommendedTargets())
                .externalId(extracted.getExternalId())
                .build();
    }

    /**
     * AI 가 보낸 코드값을 enum 으로 바꾼다. 모르는 값이면 ETC 로 떨어뜨린다 —
     * 어차피 사용자가 고칠 초안이라, AI 쪽에 값이 하나 추가된 것 때문에 500 을 낼 이유가 없다.
     */
    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("AI 가 알 수 없는 {} 값을 보냈습니다: '{}' → {} 로 대체", type.getSimpleName(), value, fallback);
            return fallback;
        }
    }

    /** 이미지에서 읽은 날짜라 형식이 어긋날 수 있다. 실패하면 비워 두고 사용자가 채우게 한다. */
    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            log.warn("AI 가 보낸 날짜를 해석하지 못했습니다: '{}'", value);
            return null;
        }
    }
}

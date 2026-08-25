package com.example.mateon.events.service;

import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.storage.BucketCapacityGuard;
import com.example.mateon.common.storage.ImageFileValidator;
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

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;

/**
 * 포스터 이미지 → 활동 등록 초안.
 *
 * <p>
 * AI 추출과 객체 저장소 업로드를 묶기만 하고 DB 는 건드리지 않는다. 저장은 사용자가 초안을
 * 확인·수정한 뒤 POST /api/events 로 하는 별도 단계다 — 그래서 EventService 를 확장하지 않고
 * 별도 서비스로 뒀다(그쪽은 전부 리포지토리 작업이다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventExtractionService {

    /**
     * AI 서버 제한(10MB)과 맞춘 값. 멀티파트 설정 뒤의 2차 방어선이다.
     */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private static final DateTimeFormatter KEY_PREFIX_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM");

    private final ContestImageExtractionClient extractionClient;
    private final ObjectStorageService objectStorageService;
    private final BucketCapacityGuard capacityGuard;

    /**
     * @throws MateonException INVALID_IMAGE_FILE(400) / IMAGE_TOO_LARGE(413) — 업로드 파일 문제,
     * STORAGE_QUOTA_EXCEEDED(507) — 버킷 총량 한도 초과,
     * AI_SERVER_UNAVAILABLE(503) / AI_SERVER_ERROR(502) — AI 서버 문제,
     * IMAGE_UPLOAD_FAILED(502) — 객체 저장소 문제
     */
    public EventExtractionResponseDTO extractFromImage(MultipartFile image) {
        ImageFileValidator.ValidatedImage validated = ImageFileValidator.validate(image, MAX_IMAGE_BYTES);
        // AI 호출 앞에 여유를 미리 본다. 어차피 뒤에서 거절될 요청에 AI 서버 비용을 쓸 이유가 없다.
        // (자리를 잡지는 않는다. 실제 판정은 아래 upload 안의 예약이 한다.)
        capacityGuard.checkRoomFor(validated.bytes().length);

        // AI 추출을 먼저 한다. 순서를 뒤집으면 판독 실패한 이미지가 버킷에 그대로 남는다
        // (초안을 못 받은 사용자는 다시 올릴 테니 지울 사람도 없다).
        ContestExtractResponse extracted = extractionClient.extract(
          validated.bytes(), image.getOriginalFilename(), validated.contentType());

        String key = "contest-images/%s/%s.%s".formatted(
          YearMonth.now().format(KEY_PREFIX_FORMAT), UUID.randomUUID(), validated.extension());
        String imageUrl = objectStorageService.upload(key, validated.bytes(), validated.contentType());

        return toDraft(extracted, imageUrl);
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

    /**
     * 이미지에서 읽은 날짜라 형식이 어긋날 수 있다. 실패하면 비워 두고 사용자가 채우게 한다.
     */
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

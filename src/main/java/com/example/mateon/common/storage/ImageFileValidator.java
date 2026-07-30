package com.example.mateon.common.storage;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 업로드된 이미지의 형식·크기 검증과 바이트 읽기. 공모전 포스터와 프로필 사진이 함께 쓴다.
 *
 * <p>허용 한도(maxBytes)를 상수로 갖지 않고 호출자에게 받는 이유: 공모전 포스터의 10MB 는
 * AI 서버 제한을 따라간 값이고, 프로필 사진의 한도는 우리 정책이다. 우연히 같은 값이라고
 * 한 곳에 묶으면 한쪽 사정이 바뀔 때 다른 쪽이 조용히 끌려간다.
 */
@Slf4j
public final class ImageFileValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    /** 확장자 → 저장소에 기록할 Content-Type. 브라우저가 보낸 값은 신뢰하지 않는다. */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png");

    private ImageFileValidator() {
    }

    /**
     * 검증을 통과한 이미지.
     *
     * @param extension 소문자 확장자 (예: "png")
     */
    public record ValidatedImage(byte[] bytes, String extension, String contentType) {
    }

    /**
     * @param maxBytes 허용 최대 크기. 초과 시 IMAGE_TOO_LARGE 인데, 그 문구가 "10MB 이하"로
     *                 고정돼 있으므로 다른 값을 넘기려면 ErrorCode 문구도 함께 손봐야 한다.
     * @throws MateonException INVALID_IMAGE_FILE(400) — 빈 파일/파일명 없음/허용 외 확장자/읽기 실패,
     *                         IMAGE_TOO_LARGE(413) — maxBytes 초과
     */
    public static ValidatedImage validate(MultipartFile image, long maxBytes) {
        String extension = validateAndResolveExtension(image, maxBytes);
        return new ValidatedImage(readBytes(image), extension, CONTENT_TYPES.get(extension));
    }

    /** 확장자를 소문자로 돌려준다. 형식/크기 문제는 전부 여기서 걸러진다. */
    private static String validateAndResolveExtension(MultipartFile image, long maxBytes) {
        if (image == null || image.isEmpty()) {
            throw new MateonException(ErrorCode.INVALID_IMAGE_FILE);
        }
        if (image.getSize() > maxBytes) {
            // 멀티파트 한도가 먼저 걸리는 게 정상이지만, 한도를 올려 잡은 환경에서도
            // 외부(AI 서버·저장소)가 거절하기 전에 우리가 먼저 안내한다.
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
     * 바이트는 한 번만 읽는다. 호출자들이 같은 배열을 여러 번(AI 전송 + 업로드) 쓰기 때문이다 —
     * MultipartFile 의 스트림을 두 번 여는 방식은 임시 파일이 이미 정리된 뒤에 터질 수 있다.
     */
    private static byte[] readBytes(MultipartFile image) {
        try {
            return image.getBytes();
        } catch (IOException e) {
            log.warn("업로드 파일을 읽지 못했습니다: {}", image.getOriginalFilename(), e);
            throw new MateonException(ErrorCode.INVALID_IMAGE_FILE);
        }
    }
}

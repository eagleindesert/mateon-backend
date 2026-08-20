package com.example.mateon.common.storage;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이미지 업로드의 입구를 고정한다. 공모전 포스터와 프로필 사진이 이 판정을 공유하므로,
 * 여기가 느슨해지면 두 기능이 함께 느슨해진다.
 */
class ImageFileValidatorTest {

    private static final long MAX = 10L * 1024 * 1024;

    private static ErrorCode errorCodeOf(Throwable e) {
        return ((MateonException) e).getErrorCode();
    }

    @Test
    @DisplayName("확장자에서 content-type 을 정하고 바이트를 함께 돌려준다")
    void resolvesContentTypeFromExtension() {
        MultipartFile file = new MockMultipartFile("image", "profile.png", "image/png", new byte[]{1, 2, 3});

        ImageFileValidator.ValidatedImage validated = ImageFileValidator.validate(file, MAX);

        assertThat(validated.extension()).isEqualTo("png");
        assertThat(validated.contentType()).isEqualTo("image/png");
        assertThat(validated.bytes()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("클라이언트가 보낸 content-type 이 아니라 확장자를 근거로 삼는다")
    void ignoresClientContentType() {
        // 브라우저·OS 에 따라 octet-stream 으로 오는 경우가 있다. 그때도 jpg 는 jpg 로 다뤄야 한다.
        MultipartFile file = new MockMultipartFile(
          "image", "profile.jpg", "application/octet-stream", new byte[]{1});

        assertThat(ImageFileValidator.validate(file, MAX).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("대문자 확장자도 허용하고 소문자로 정규화한다")
    void normalizesUppercaseExtension() {
        MultipartFile file = new MockMultipartFile("image", "PROFILE.JPEG", "image/jpeg", new byte[]{1});

        ImageFileValidator.ValidatedImage validated = ImageFileValidator.validate(file, MAX);

        assertThat(validated.extension()).isEqualTo("jpeg");
        assertThat(validated.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("허용 목록에 없는 확장자는 400")
    void rejectsUnsupportedExtension() {
        MultipartFile gif = new MockMultipartFile("image", "profile.gif", "image/gif", new byte[]{1});

        assertThatThrownBy(() -> ImageFileValidator.validate(gif, MAX))
          .isInstanceOf(MateonException.class)
          .extracting(ImageFileValidatorTest::errorCodeOf)
          .isEqualTo(ErrorCode.INVALID_IMAGE_FILE);
    }

    @Test
    @DisplayName("확장자가 없으면 400")
    void rejectsMissingExtension() {
        MultipartFile file = new MockMultipartFile("image", "profile", "image/png", new byte[]{1});

        assertThatThrownBy(() -> ImageFileValidator.validate(file, MAX))
          .isInstanceOf(MateonException.class)
          .extracting(ImageFileValidatorTest::errorCodeOf)
          .isEqualTo(ErrorCode.INVALID_IMAGE_FILE);
    }

    @Test
    @DisplayName("파일명이 비어 있으면 400")
    void rejectsBlankFilename() {
        MultipartFile file = new MockMultipartFile("image", "", "image/png", new byte[]{1});

        assertThatThrownBy(() -> ImageFileValidator.validate(file, MAX))
          .isInstanceOf(MateonException.class)
          .extracting(ImageFileValidatorTest::errorCodeOf)
          .isEqualTo(ErrorCode.INVALID_IMAGE_FILE);
    }

    @Test
    @DisplayName("빈 파일과 null 은 400")
    void rejectsEmptyAndNull() {
        MultipartFile empty = new MockMultipartFile("image", "profile.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> ImageFileValidator.validate(empty, MAX))
          .isInstanceOf(MateonException.class)
          .extracting(ImageFileValidatorTest::errorCodeOf)
          .isEqualTo(ErrorCode.INVALID_IMAGE_FILE);

        assertThatThrownBy(() -> ImageFileValidator.validate(null, MAX))
          .isInstanceOf(MateonException.class)
          .extracting(ImageFileValidatorTest::errorCodeOf)
          .isEqualTo(ErrorCode.INVALID_IMAGE_FILE);
    }

    @Test
    @DisplayName("한도를 넘으면 413 (형식 오류와 구분한다)")
    void rejectsOversizedFile() {
        MultipartFile huge = new MockMultipartFile(
          "image", "profile.png", "image/png", new byte[(int) MAX + 1]);

        assertThatThrownBy(() -> ImageFileValidator.validate(huge, MAX))
          .isInstanceOf(MateonException.class)
          .extracting(ImageFileValidatorTest::errorCodeOf)
          .isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
    }

    @Test
    @DisplayName("한도는 호출자가 정한다 (공모전 10MB 와 별개로 움직인다)")
    void honoursCallerLimit() {
        MultipartFile file = new MockMultipartFile("image", "profile.png", "image/png", new byte[100]);

        assertThat(ImageFileValidator.validate(file, 100).bytes()).hasSize(100);
        assertThatThrownBy(() -> ImageFileValidator.validate(file, 99))
          .isInstanceOf(MateonException.class)
          .extracting(ImageFileValidatorTest::errorCodeOf)
          .isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
    }
}

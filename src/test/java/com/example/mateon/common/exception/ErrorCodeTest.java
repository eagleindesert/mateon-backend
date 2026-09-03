package com.example.mateon.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ErrorCode#toException()} 이 코드와 문구를 그대로 실어 나르는지 고정한다.
 *
 * <p>
 * 서비스들은 {@code Optional.orElseThrow} 에 메서드 레퍼런스로 이 메서드를 넘긴다.
 * 생성 로직이 여기 한곳이라, 코드→예외 매핑이 어긋나면 전 도메인의 빈 Optional 응답이
 * 한꺼번에 틀린다.
 */
class ErrorCodeTest {

    @Test
    @DisplayName("toException 은 그 코드와 메시지를 담은 MateonException 이다")
    void toExceptionCarriesCodeAndMessage() {
        MateonException exception = ErrorCode.USER_NOT_FOUND.toException();

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo(ErrorCode.USER_NOT_FOUND.getMessage());
    }
}

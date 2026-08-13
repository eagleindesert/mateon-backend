package com.example.mateon.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 페이지 파라미터 정규화. 세 줄짜리 유틸이지만 <b>공개 계약</b>이라 값 자체를 못박는다.
 *
 * <p>프론트는 "돌아온 배열 길이가 요청한 size 와 같으면 다음 페이지가 있다"로 무한 스크롤을
 * 판단한다. 그래서 상한 100 은 서버 내부 사정이 아니다 — 프론트가 {@code size=200} 을 보내고
 * 100건을 받으면 "딱 맞지 않으니 마지막 페이지"로 읽어 <b>나머지를 영영 안 불러온다</b>.
 * 이 값이 조용히 바뀌면 목록이 특정 지점에서 멈추는데, 에러도 로그도 없다.
 *
 * <p>{@code clampSize(0) = 1} 도 마찬가지다. 0 을 그대로 넘기면 {@code PageRequest.of} 가
 * {@code IllegalArgumentException} 을 던져 500 이 된다 — 클라이언트 실수를 서버 오류로
 * 바꾸지 않으려는 장치다.
 */
class PageLimitsTest {

    @Nested
    @DisplayName("clampPage")
    class ClampPage {

        @Test
        @DisplayName("음수는 0 으로 올린다")
        void negativeBecomesZero() {
            assertThat(PageLimits.clampPage(-1)).isZero();
            assertThat(PageLimits.clampPage(Integer.MIN_VALUE)).isZero();
        }

        @Test
        @DisplayName("0 이상은 그대로 둔다 (상한이 없다)")
        void nonNegativePassesThrough() {
            assertThat(PageLimits.clampPage(0)).isZero();
            assertThat(PageLimits.clampPage(1)).isEqualTo(1);
            assertThat(PageLimits.clampPage(9999)).isEqualTo(9999);
        }
    }

    @Nested
    @DisplayName("clampSize")
    class ClampSize {

        @Test
        @DisplayName("0 과 음수는 1 로 올린다 — PageRequest.of 가 터지는 것을 막는다")
        void tooSmallBecomesOne() {
            assertThat(PageLimits.clampSize(0)).isEqualTo(1);
            assertThat(PageLimits.clampSize(-5)).isEqualTo(1);
        }

        @Test
        @DisplayName("상한은 정확히 100 이다 (프론트의 '다음 페이지 있음' 판단 기준)")
        void upperBoundIs100() {
            assertThat(PageLimits.MAX_PAGE_SIZE).isEqualTo(100);
            assertThat(PageLimits.clampSize(100)).isEqualTo(100);
            assertThat(PageLimits.clampSize(101)).isEqualTo(100);
            assertThat(PageLimits.clampSize(100_000)).isEqualTo(100);
        }

        @Test
        @DisplayName("1 과 99 는 그대로 통과한다 (경계 안쪽)")
        void insideRangePassesThrough() {
            assertThat(PageLimits.clampSize(1)).isEqualTo(1);
            assertThat(PageLimits.clampSize(20)).isEqualTo(20);
            assertThat(PageLimits.clampSize(99)).isEqualTo(99);
        }
    }
}

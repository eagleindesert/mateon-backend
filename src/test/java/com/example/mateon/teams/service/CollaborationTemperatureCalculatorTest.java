package com.example.mateon.teams.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CollaborationTemperatureCalculatorTest {

    /** 전부 5점을 받은 경우의 합. */
    private static int allFives(int n) {
        return n * 5;
    }

    private static BigDecimal temp(int count, int sum) {
        return CollaborationTemperatureCalculator.temperature(count, sum);
    }

    @Nested
    @DisplayName("표본 부족 시 비공개")
    class Undisclosed {

        @Test
        @DisplayName("평가가 없으면 null 이다")
        void noReviews() {
            assertThat(temp(0, 0)).isNull();
        }

        @Test
        @DisplayName("평가가 1건이면 null 이다 — 누가 줬는지 특정되므로 익명성이 깨진다")
        void singleReviewStaysHidden() {
            assertThat(temp(1, 5)).isNull();
        }

        @Test
        @DisplayName("2건부터 공개한다")
        void twoReviewsDisclosed() {
            assertThat(temp(2, 10)).isNotNull();
        }
    }

    @Nested
    @DisplayName("기준점")
    class Baseline {

        @Test
        @DisplayName("중립(3점)만 받으면 건수와 무관하게 36.5 다")
        void neutralAlwaysBase() {
            assertThat(temp(2, 6)).isEqualByComparingTo("36.5");
            assertThat(temp(30, 90)).isEqualByComparingTo("36.5");
            assertThat(temp(300, 900)).isEqualByComparingTo("36.5");
        }
    }

    @Nested
    @DisplayName("만점을 받았을 때의 상승 곡선")
    class PerfectScores {

        // 초반에는 36.5 근처에서 완만히 오르고, 누적될수록 상한에 접근한다.
        // 이 값들이 바뀌면 계수(C, K)를 건드린 것이므로 ADR 도 함께 갱신해야 한다.

        @Test
        @DisplayName("3건이면 39.6 — 첫 팀만으로는 크게 오르지 않는다")
        void threeReviews() {
            assertThat(temp(3, allFives(3))).isEqualByComparingTo("39.6");
        }

        @Test
        @DisplayName("10건이면 50.4")
        void tenReviews() {
            assertThat(temp(10, allFives(10))).isEqualByComparingTo("50.4");
        }

        @Test
        @DisplayName("30건이면 68.6")
        void thirtyReviews() {
            assertThat(temp(30, allFives(30))).isEqualByComparingTo("68.6");
        }

        @Test
        @DisplayName("100건이면 86.1")
        void hundredReviews() {
            assertThat(temp(100, allFives(100))).isEqualByComparingTo("86.1");
        }

        @Test
        @DisplayName("현실적인 누적량(1000건)에서도 상한 근처에 가지 않는다")
        void staysWellBelowMaxAtRealisticVolume() {
            assertThat(temp(1_000, allFives(1_000))).isEqualByComparingTo("97.5");
        }

        @Test
        @DisplayName("어떤 입력에서도 99 를 넘지 않는다")
        void neverExceedsMax() {
            assertThat(temp(100_000, allFives(100_000)))
                    .isLessThanOrEqualTo(BigDecimal.valueOf(CollaborationTemperatureCalculator.MAX_TEMP));
        }
    }

    @Nested
    @DisplayName("최저점을 받았을 때의 하락 곡선")
    class WorstScores {

        @Test
        @DisplayName("3건이면 34.7")
        void threeReviews() {
            assertThat(temp(3, 3)).isEqualByComparingTo("34.7");
        }

        @Test
        @DisplayName("30건이면 17.7")
        void thirtyReviews() {
            assertThat(temp(30, 30)).isEqualByComparingTo("17.7");
        }

        @Test
        @DisplayName("0 밑으로 내려가지 않는다")
        void neverBelowMin() {
            assertThat(temp(100_000, 100_000))
                    .isGreaterThanOrEqualTo(BigDecimal.valueOf(CollaborationTemperatureCalculator.MIN_TEMP));
        }

        @Test
        @DisplayName("상승과 하락은 각자의 여유분 대비 같은 비율만큼 움직인다 (비율 대칭)")
        void movesSymmetricallyInProportion() {
            BigDecimal base = BigDecimal.valueOf(CollaborationTemperatureCalculator.BASE_TEMP);

            double gainedRatio = temp(30, allFives(30)).subtract(base).doubleValue()
                    / (CollaborationTemperatureCalculator.MAX_TEMP - CollaborationTemperatureCalculator.BASE_TEMP);
            double lostRatio = base.subtract(temp(30, 30)).doubleValue()
                    / (CollaborationTemperatureCalculator.BASE_TEMP - CollaborationTemperatureCalculator.MIN_TEMP);

            assertThat(gainedRatio).isCloseTo(lostRatio, within(0.01));
        }
    }

    @Nested
    @DisplayName("단조성")
    class Monotonicity {

        @Test
        @DisplayName("같은 평점이면 건수가 늘수록 온도가 오른다 — 검증된 실적일수록 높다")
        void moreEvidenceRaisesTemperature() {
            BigDecimal previous = temp(2, allFives(2));
            for (int n = 3; n <= 200; n++) {
                BigDecimal current = temp(n, allFives(n));
                assertThat(current).isGreaterThanOrEqualTo(previous);
                previous = current;
            }
        }

        @Test
        @DisplayName("같은 건수면 평점 합이 클수록 온도가 높다")
        void betterRatingsRaiseTemperature() {
            BigDecimal previous = temp(10, 10); // 전부 1점
            for (int sum = 11; sum <= 50; sum++) {
                BigDecimal current = temp(10, sum);
                assertThat(current).isGreaterThanOrEqualTo(previous);
                previous = current;
            }
        }
    }
}

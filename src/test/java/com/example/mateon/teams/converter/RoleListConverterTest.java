package com.example.mateon.teams.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code teams.role}/{@code required_skills} 의 CSV ↔ List 변환.
 *
 * <p>여기서 고정할 것은 값 하나하나가 아니라 <b>비대칭성</b>이다. 쓸 때는 빈 리스트가
 * {@code null} 컬럼이 되고, 읽을 때는 {@code null} 컬럼이 <b>빈 리스트</b>가 된다
 * ({@code null} 이 아니다). 이 비대칭이 팀 카드가 {@code team.getRole().stream()} 을 바로
 * 부를 수 있는 근거다 — 읽기 쪽이 {@code null} 을 돌려주도록 "대칭적으로" 고치면 팀 목록
 * 전체가 NPE 로 죽는다.
 *
 * <p>또 하나는 <b>원소 안의 콤마</b>다. 왕복이 깨지는 걸 알면서 허용한 한계라, 고장이 아니라
 * 계약이라는 걸 테스트로 남긴다 — 나중에 자유 입력 필드에 이 컨버터를 붙이려는 사람이 여기서
 * 걸린다.
 */
class RoleListConverterTest {

    private RoleListConverter converter;

    @BeforeEach
    void setUp() {
        converter = new RoleListConverter();
    }

    @Nested
    @DisplayName("List → 컬럼")
    class ToColumn {

        @Test
        @DisplayName("콤마로 join 한다 (공백 없이)")
        void joinsWithComma() {
            assertThat(converter.convertToDatabaseColumn(List.of("데이터 분석", "기획")))
                    .isEqualTo("데이터 분석,기획");
        }

        @Test
        @DisplayName("null 과 빈 리스트는 null 컬럼이다 (빈 문자열이 아니다)")
        void nullAndEmptyBecomeNull() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
            assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
        }

        @Test
        @DisplayName("원소의 앞뒤 공백은 잘라내고, 비어 있는 원소는 버린다")
        void trimsAndDropsBlanks() {
            assertThat(converter.convertToDatabaseColumn(Arrays.asList("  백엔드 ", "", "   ", "기획")))
                    .isEqualTo("백엔드,기획");
        }

        @Test
        @DisplayName("null 원소가 섞여 있어도 NPE 없이 버린다 (프론트가 보낸 배열이 그대로 온다)")
        void dropsNullElements() {
            List<String> withNull = new ArrayList<>();
            withNull.add("백엔드");
            withNull.add(null);
            withNull.add("기획");

            assertThat(converter.convertToDatabaseColumn(withNull)).isEqualTo("백엔드,기획");
        }

        /**
         * 리스트에 원소가 <i>있는데</i> 전부 공백이면 위의 "빈 리스트 → null" 분기를 타지 않아
         * 빈 문자열이 저장된다. 읽을 때 다시 빈 리스트가 되므로 화면에는 티가 안 나지만,
         * DB 에는 {@code null} 과 {@code ''} 두 가지 "없음"이 공존하게 된다 —
         * {@code WHERE role IS NULL} 로 조회하는 쿼리를 쓸 때 걸린다.
         */
        @Test
        @DisplayName("전부 공백인 리스트는 null 이 아니라 빈 문자열이 된다 (DB 에 '없음'이 두 종류가 된다)")
        void allBlankBecomesEmptyStringNotNull() {
            assertThat(converter.convertToDatabaseColumn(List.of("  ", "\t"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("컬럼 → List")
    class ToAttribute {

        @Test
        @DisplayName("콤마로 나누고 각 원소를 트림한다")
        void splitsAndTrims() {
            assertThat(converter.convertToEntityAttribute(" 백엔드 , 기획 "))
                    .containsExactly("백엔드", "기획");
        }

        @Test
        @DisplayName("null·빈 문자열·공백뿐인 컬럼은 빈 리스트다 — null 이 아니다")
        void nullColumnBecomesEmptyList() {
            assertThat(converter.convertToEntityAttribute(null)).isEmpty();
            assertThat(converter.convertToEntityAttribute("")).isEmpty();
            assertThat(converter.convertToEntityAttribute("   ")).isEmpty();
        }

        @Test
        @DisplayName("연속된 콤마로 생긴 빈 조각은 버린다")
        void dropsEmptySegments() {
            assertThat(converter.convertToEntityAttribute("a,, ,b,"))
                    .containsExactly("a", "b");
        }
    }

    @Test
    @DisplayName("보통 값은 왕복해도 그대로다")
    void roundTrip() {
        List<String> original = List.of("백엔드", "프론트엔드", "데이터 분석");

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(original)))
                .isEqualTo(original);
    }

    /**
     * 알려진 한계다. 역할 코드·스킬처럼 콤마가 들어갈 일 없는 값에만 쓰기로 하고 받아들였다
     * ({@code StringListConverter} 의 주석에 같은 판단이 적혀 있다). 자유 입력 필드에 이
     * 컨버터를 붙이면 사용자가 친 콤마 하나가 원소를 둘로 쪼갠다.
     */
    @Test
    @DisplayName("원소 안에 콤마가 있으면 왕복하지 않는다 (알려진 한계 — 고장이 아니라 계약이다)")
    void commaInsideValueBreaksRoundTrip() {
        List<String> original = List.of("기획, 마케팅");

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(original)))
                .containsExactly("기획", "마케팅");
    }
}

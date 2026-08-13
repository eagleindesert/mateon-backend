package com.example.mateon.matching.converter;

import com.example.mateon.teams.converter.RoleListConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code matching_intent_slots} 의 CSV ↔ List 변환.
 *
 * <p>이 클래스는 {@link RoleListConverter} 의 <b>의도적 복제</b>다 — matching → teams 방향의
 * import 를 만들지 않으려고 로직을 그대로 베꼈다 (원본 주석에 그렇게 적혀 있다).
 *
 * <p>복제이기 때문에 위험한 건 "둘이 갈라지는 것"이다. 한쪽만 고쳐도 컴파일은 통과하고,
 * 증상은 "슬롯의 스킬 목록과 팀의 스킬 목록이 미묘하게 다르게 파싱된다"로 나타나 추천 점수에만
 * 조용히 반영된다. 그래서 개별 규칙을 되풀이해 적는 대신 <b>두 컨버터가 같은 입력에 같은 답을
 * 내는지</b>를 직접 단언한다 — 갈라지는 순간 여기서 걸린다.
 *
 * <p>세부 규칙(빈 리스트 → null 컬럼, null 컬럼 → 빈 리스트, 콤마 한계)의 근거는
 * {@code RoleListConverterTest} 에 적어 두었다.
 */
class StringListConverterTest {

    private StringListConverter converter;
    private RoleListConverter twin;

    @BeforeEach
    void setUp() {
        converter = new StringListConverter();
        twin = new RoleListConverter();
    }

    @Test
    @DisplayName("콤마로 join 하고, null·빈 리스트는 null 컬럼이다")
    void toColumn() {
        assertThat(converter.convertToDatabaseColumn(List.of("BE", "FE"))).isEqualTo("BE,FE");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
    }

    @Test
    @DisplayName("null 컬럼은 빈 리스트다 — 슬롯을 읽는 쪽이 null 검사를 하지 않는다")
    void nullColumnBecomesEmptyList() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("  ")).isEmpty();
    }

    @Test
    @DisplayName("AI 가 준 값을 왕복해도 그대로다")
    void roundTrip() {
        List<String> original = List.of("BE", "FE", "PM");

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(original)))
                .isEqualTo(original);
    }

    @Test
    @DisplayName("쌍둥이 컨버터와 결과가 완전히 같다 — 한쪽만 고치면 여기서 걸린다")
    void behavesIdenticallyToRoleListConverter() {
        List<String> withNullElement = new ArrayList<>();
        withNullElement.add("BE");
        withNullElement.add(null);
        withNullElement.add(" FE ");

        List<List<String>> attributeCases = Arrays.asList(
                null,
                List.of(),
                List.of("BE"),
                List.of("BE", "FE"),
                List.of(" BE ", "  ", "FE"),
                List.of("  ", "\t"),
                withNullElement,
                List.of("기획, 마케팅"));

        for (List<String> attribute : attributeCases) {
            assertThat(converter.convertToDatabaseColumn(attribute))
                    .as("컬럼 변환이 갈라졌다: %s", attribute)
                    .isEqualTo(twin.convertToDatabaseColumn(attribute));
        }

        List<String> columnCases = Arrays.asList(
                null, "", "   ", "BE", "BE,FE", " BE , FE ", "a,, ,b,", ",", "기획, 마케팅");

        for (String column : columnCases) {
            assertThat(converter.convertToEntityAttribute(column))
                    .as("엔티티 변환이 갈라졌다: [%s]", column)
                    .isEqualTo(twin.convertToEntityAttribute(column));
        }
    }
}

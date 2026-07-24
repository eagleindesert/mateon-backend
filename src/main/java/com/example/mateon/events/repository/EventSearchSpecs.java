package com.example.mateon.events.repository;

import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 활동 검색 조건 조립.
 *
 * <p>
 * 필터는 전부 선택이고 조합이 자유롭다. 조합마다 전용 쿼리를 두면 축이 하나 늘 때마다 쿼리 수가
 * 배로 늘어나므로(4축이면 16가지), 지정된 조건만 AND 로 이어 붙이는 방식으로 만든다.
 * 조건 하나도 안 걸리면 제약 없는 Specification 이 되어 전체 조회와 같아진다.
 */
public final class EventSearchSpecs {

    private EventSearchSpecs() {
    }

    /**
     * 이 문자열이 오면 검색어를 안 준 것과 같이 취급한다("전체" = 필터 미적용).
     * API 스펙(docs/api-spec)에 "생략하거나 '전체' 입력 시 전체 조회"로 명시된 계약이고,
     * 프론트가 이미 이 규약을 적용해 '전체' 칩 선택 시 이 문자열을 보낸다 — 임의로 없애면 화면이 깨진다.
     *
     * <p>
     * <b>폐기 예정.</b> '전체'를 별도 문자열로 흘려보내는 방식 자체가 미전송(null)으로 충분한 걸
     * 우회하는 형태다. 프론트가 '전체' 선택 시 파라미터를 아예 보내지 않도록 전환되면, 이 상수와
     * {@link #contains} 의 관련 분기, v6-2.md 의 "'전체' 입력 시" 문구를 함께 걷어낸다.
     * 전환 완료 전까지는 프론트 호환을 위해 그대로 둔다.
     */
    private static final String NO_FILTER = "전체";

    public static Specification<Event> of(String college, String school, Category category, Field field,
      String keyword) {
        // 미지정 필터는 null 로 돌아온다. allOf 가 null 을 거부하므로 여기서 걸러낸다.
        return Specification.allOf(Stream.of(
          equals("category", category),
          equals("field", field),
          // 대상 범위는 target_school 로 옮겨가는 중이지만, 기존 데이터는 target_colleges 에 있다.
          contains("target_colleges", college),
          contains("targetSchool", school),
          // 키워드는 여러 컬럼 중 하나만 맞아도 되므로(OR) 한 덩어리로 묶어 필터들과 AND 로 붙인다.
          keywordMatch(keyword))
          .filter(Objects::nonNull)
          .toList());
    }

    /**
     * 제목·설명·주최를 아우르는 자유 키워드 검색. 세 컬럼 중 하나라도 부분일치하면 매칭이므로 OR 로 묶는다
     * (필터는 컬럼마다 AND 인 것과 대비된다). 사용자는 어느 필드에 어휘가 들어있는지 모르고 찾기 때문이다.
     *
     * <p>
     * 키워드가 비었거나 '전체' 면 세 서브 스펙이 모두 null 이 된다. 빈 anyOf 는 '항상 참'이 되어 오히려
     * 조건이 걸린 것처럼 오작동하므로, 이 경우 스펙 자체를 null 로 돌려 조건 목록에서 빠지게 한다.
     */
    private static Specification<Event> keywordMatch(String keyword) {
        List<Specification<Event>> parts = Stream.of(
          contains("title", keyword),
          contains("description", keyword),
          contains("organizer", keyword))
          .filter(Objects::nonNull)
          .toList();
        return parts.isEmpty() ? null : Specification.anyOf(parts);
    }

    private static Specification<Event> equals(String attribute, Object value) {
        if (value == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get(attribute), value);
    }

    /**
     * 자유 입력 문자열의 부분일치. 표기가 흔들리고("단국대" ↔ "단국대학교") 콤마로 여러 값이
     * 들어오기도 해서 정확 일치로는 못 건진다.
     *
     * <p>
     * 값이 NULL 인 행은 LIKE 가 참이 되지 않아 자연히 빠진다 — 대상 학교가 없는(전국 대상) 활동은
     * 특정 학교로 좁힌 검색에 잡히지 않는다는 뜻이고, 의도한 동작이다.
     */
    private static Specification<Event> contains(String attribute, String keyword) {
        if (keyword == null || keyword.trim().isEmpty() || keyword.trim().equalsIgnoreCase(NO_FILTER)) {
            return null;
        }
        String pattern = "%" + keyword.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get(attribute)), pattern);
    }
}

package com.example.mateon.events.repository;

import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import org.springframework.data.jpa.domain.Specification;

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

    /** 검색어를 안 준 것과 같이 취급하는 값. 프론트의 '전체' 칩이 이 문자열을 보낸다. */
    private static final String NO_FILTER = "전체";

    public static Specification<Event> of(String college, String school, Category category, Field field) {
        // 미지정 필터는 null 로 돌아온다. allOf 가 null 을 거부하므로 여기서 걸러낸다.
        return Specification.allOf(Stream.of(
                        equals("category", category),
                        equals("field", field),
                        // 대상 범위는 target_school 로 옮겨가는 중이지만, 기존 데이터는 target_colleges 에 있다.
                        contains("target_colleges", college),
                        contains("targetSchool", school))
                .filter(Objects::nonNull)
                .toList());
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

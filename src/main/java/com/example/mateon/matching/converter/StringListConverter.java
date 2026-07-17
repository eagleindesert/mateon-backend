package com.example.mateon.matching.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * List&lt;String&gt; ↔ CSV 문자열 변환. (예: ["BE", "FE"] ↔ "BE,FE")
 *
 * teams/converter/RoleListConverter 와 로직이 같지만, 도메인 간 import(matching → teams)를
 * 만들지 않기 위해 복제했다. autoApply=false 이므로 @Convert 로 명시한 필드에만 적용된다.
 *
 * 한계: 원소 안에 콤마가 있으면 깨진다. AI 가 리스트로 주는 값(역할 코드/스킬/관심사)이라
 * 원소에 콤마가 들어갈 일이 없어 허용한다.
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return attribute.stream()
                .filter(s -> s != null && !s.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.joining(","));
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(dbData.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

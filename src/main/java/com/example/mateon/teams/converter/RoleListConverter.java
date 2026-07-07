package com.example.mateon.teams.converter; // converter 패키지로 가정

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Converter
public class RoleListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        // List -> String (예: "데이터 분석, 기획")
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
        // String -> List (예: ["데이터 분석", "기획"])
        return Arrays.stream(dbData.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
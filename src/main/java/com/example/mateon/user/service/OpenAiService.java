package com.example.mateon.user.service;

import com.example.mateon.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OpenAiService {

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.model:gpt-4o-mini}")
    private String model;

    private final RestTemplate restTemplate;

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    /**
     * 사용자의 활동 이력을 바탕으로 직무 적합도 분석 (Fit & Gap)을 생성합니다.
     * @param user 사용자 정보
     * @param activityTitles 참여한 활동 제목 목록
     * @return JSON 형태의 분석 결과 문자열
     */
    public String generateDreamyAnalysis(User user, List<String> activityTitles) {
        try {
            // 활동 목록을 문자열로 변환
            String activityList = activityTitles.isEmpty()
                    ? "없음"
                    : String.join(", ", activityTitles);

            // 시스템 프롬프트: AI의 역할과 응답 형식 정의
            String systemPrompt = "당신은 대학생 커리어 컨설턴트 '드림이'입니다. " +
                    "학생의 전공, 학년, 활동 내역을 분석하여 희망 직무와의 적합도를 평가해주세요. " +
                    "응답은 반드시 아래 JSON 형식으로만 답변하세요. 다른 설명이나 추가 텍스트는 절대 포함하지 마세요.\n" +
                    "{\n" +
                    "  \"score\": (0~100 사이 숫자),\n" +
                    "  \"strength\": \"(강점을 1줄로 요약, 100자 이내)\",\n" +
                    "  \"weakness\": \"(보완점을 1줄로 요약, 100자 이내)\",\n" +
                    "  \"recommendedAction\": \"(구체적으로 추천하는 다음 활동 1개, 100자 이내)\"\n" +
                    "}";

            // 사용자 프롬프트: 분석할 학생 정보
            String userPrompt = String.format(
                    "전공: %s, 학년: %s학년, 희망직무: %s, 한줄소개: %s, 참여활동: %s",
                    user.getMajor() != null ? user.getMajor() : "미입력",
                    user.getGrade() != null ? user.getGrade() : "미입력",
                    user.getInterestJobPrimary() != null ? user.getInterestJobPrimary() : "미입력",
                    user.getTagline() != null ? user.getTagline() : "미입력",
                    activityList
            );

            // 요청 바디 생성
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 500);

            // 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // API 호출
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = restTemplate.postForObject(OPENAI_API_URL, entity, Map.class);

            if (responseBody == null) {
                return getDefaultAnalysisJson();
            }

            // 응답 파싱
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            if (choices == null || choices.isEmpty()) {
                return getDefaultAnalysisJson();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");

            // JSON 추출 (마크다운 코드 블록 제거)
            content = content.trim();
            if (content.startsWith("```json")) {
                content = content.substring(7);
            }
            if (content.startsWith("```")) {
                content = content.substring(3);
            }
            if (content.endsWith("```")) {
                content = content.substring(0, content.length() - 3);
            }
            content = content.trim();

            return content;

        } catch (Exception e) {
            // 에러 발생 시 기본값 반환
            System.err.println("OpenAI API 호출 실패: " + e.getMessage());
            e.printStackTrace();
            return getDefaultAnalysisJson();
        }
    }

    /**
     * 기본 분석 결과 JSON 반환 (에러 발생 시)
     */
    private String getDefaultAnalysisJson() {
        return "{\"score\": 50, \"strength\": \"활동 데이터를 충분히 채워주세요.\", \"weakness\": \"더 많은 활동 경험을 쌓아보세요.\", \"recommendedAction\": \"관심있는 분야의 공모전이나 대외활동에 참여해보세요.\"}";
    }
}
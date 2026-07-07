package com.example.mateon;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class MateonBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MateonBackendApplication.class, args);
    }

    // 안전하게 설정된 ObjectMapper 빈 등록
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                // 1. 날짜 에러 방지 (Java 8 날짜/시간 모듈 등록) ⭐ 중요!
                .registerModule(new JavaTimeModule())
                // 2. 날짜를 [2024, 12, 15] 배열이 아닌 "2024-12-15" 문자열로 출력
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // 3. 모르는 필드가 있어도 에러 안 내고 무시함 (안전 장치)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
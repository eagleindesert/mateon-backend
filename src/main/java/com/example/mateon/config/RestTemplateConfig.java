package com.example.mateon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /**
     * 범용 RestTemplate (카카오/OpenAI 호출용).
     *
     * matching/config/AiRestTemplateConfig 가 AI 전용 빈(aiRestTemplate)을 별도로 등록하므로
     * RestTemplate 타입 빈이 2개다. @Primary 로 기존 주입 지점(KakaoOAuthClient, OpenAiService)의
     * 해석을 파라미터 이름 폴백에 맡기지 않고 명시적으로 보존한다.
     */
    @Primary
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

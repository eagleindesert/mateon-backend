package com.example.mateon.matching.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
@RequiredArgsConstructor
public class AiRestTemplateConfig {

    private final AiServerProperties properties;

    /**
     * AI 서버 전용 RestTemplate.
     *
     * 기본 restTemplate 빈(config/RestTemplateConfig)은 타임아웃이 없어 무한 대기하지만,
     * 카카오/OpenAI 호출의 동작을 바꾸지 않기 위해 그대로 두고 여기서 별도 빈을 만든다.
     *
     * Spring Boot 4 에서 RestTemplateBuilder 는 spring-boot-restclient 모듈로 이동했고,
     * 이 프로젝트엔 test 스코프로만 들어와 있다(main 클래스패스에 없음). 그래서 Builder 대신
     * spring-web 에 내장된 SimpleClientHttpRequestFactory 를 직접 구성한다 — 의존성 추가 0.
     */
    @Bean
    public RestTemplate aiRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        return new RestTemplate(factory);
    }
}

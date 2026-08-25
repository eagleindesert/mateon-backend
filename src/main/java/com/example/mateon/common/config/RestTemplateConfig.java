package com.example.mateon.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    /**
     * 카카오는 대화형 로그인 경로라 사용자가 기다린다 — 죽은 서버를 오래 물고 있을 이유가 없다.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 범용 RestTemplate (카카오 OAuth 호출용).
     *
     * common/ai/AiRestTemplateConfig 가 AI 전용 빈(aiRestTemplate)을 별도로 등록하므로
     * RestTemplate 타입 빈이 2개다. @Primary 로 기존 주입 지점(KakaoOAuthClient)의
     * 해석을 파라미터 이름 폴백에 맡기지 않고 명시적으로 보존한다.
     *
     * 타임아웃은 필수다. 안 주면 무한 대기라, 카카오가 응답 없이 매달리면 요청 스레드가
     * 영영 돌아오지 않는다. (AiRestTemplateConfig 와 같은 이유로 Builder 대신
     * SimpleClientHttpRequestFactory 를 직접 구성한다 — main 클래스패스에 Builder 가 없다.)
     */
    @Primary
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(factory);
    }
}

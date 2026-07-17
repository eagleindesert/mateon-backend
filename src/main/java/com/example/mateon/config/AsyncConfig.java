package com.example.mateon.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * @Async 활성화 (횡단 관심사라 config/ 에 둔다).
 *
 * <p>executor 빈을 직접 정의하지 않는 이유: Boot 가 자동 구성하는
 * applicationTaskExecutor(ThreadPoolTaskExecutor) 를 @Async 가 기본으로 사용한다.
 *
 * <p>현재 사용처: 팀 임베딩 갱신(TeamEmbeddingRefreshListener) — AI 호출이 최대 60초라
 * HTTP 응답 스레드에서 분리한다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}

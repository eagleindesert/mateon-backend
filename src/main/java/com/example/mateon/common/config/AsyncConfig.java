package com.example.mateon.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @Async 활성화 (횡단 관심사라 config/ 에 둔다).
 *
 * <p>
 * 기본 executor 는 Boot 가 자동 구성하는 applicationTaskExecutor 다. 팀 임베딩 갱신은
 * 그걸 그대로 쓴다.
 *
 * <p>
 * 공모전 임베딩만 별도 풀인 이유: 크롤러가 POST /api/events 를 수백 건 넣으면 기본 풀이
 * FastAPI 를 동시에 때린다. core/max 2 로 묶어 AI 서버를 보호한다. 큐가 가득 차면
 * 제출한 스레드에서 실행한다 — 버리는 것보다 등록 직후 한 번 더 기다리는 편이 낫다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "contestEmbeddingExecutor")
    public Executor contestEmbeddingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("contest-embedding-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}

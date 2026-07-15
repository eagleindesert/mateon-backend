package com.example.mateon.matching.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 별도 FastAPI AI 서버 설정. (config/JwtProperties 와 같은 형태)
 *
 * JwtProperties 가 config/ 에 있는 건 JWT 가 횡단 관심사여서다.
 * AI 설정은 matching 도메인 전용이라 도메인 우선 패키징 원칙에 따라 여기 둔다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai")
public class AiServerProperties {

    /** FastAPI 베이스 URL. */
    private String baseUrl;

    /**
     * AI 서버와의 공유 시크릿. 매 요청의 X-Internal-Secret 헤더로 보낸다.
     * .env 의 AI_INTERNAL_SECRET 에서 주입되며, 없으면 부팅이 실패한다.
     */
    private String internalSecret;

    /** 연결 타임아웃. 짧게 — 서버가 죽었으면 빨리 알아야 한다. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 응답 타임아웃. LLM+임베딩이라 수 초~수십 초 걸리므로 넉넉히. */
    private Duration readTimeout = Duration.ofSeconds(60);

    /** 이 시간 넘게 방치된 IN_PROGRESS 세션은 만료로 본다 (지연 만료). */
    private Duration sessionTtl = Duration.ofHours(24);

    /** 임베딩 차원. user_embeddings.embedding 이 vector(1536) 이므로 반드시 일치해야 한다. */
    private int embeddingDimension = 1536;

    /**
     * 시크릿 누락을 부팅 시점에 잡는다.
     *
     * <p>@ConfigurationProperties 바인딩은 @Value 와 달리 해결하지 못한 플레이스홀더를 예외 없이
     * 무시하고 원문("${AI_INTERNAL_SECRET}")을 그대로 넣는다. 그래서 프로퍼티에 기본값을 안 주는
     * 것만으로는 부팅이 실패하지 않고, 그 리터럴이 헤더로 나가 실서버에서 원인 모를 401 이 된다.
     * @NotBlank 로도 못 걸러진다(빈 문자열이 아니므로). 그래서 직접 확인한다.
     */
    @PostConstruct
    void validateInternalSecret() {
        if (!StringUtils.hasText(internalSecret) || internalSecret.startsWith("${")) {
            throw new IllegalStateException(
                    "ai.internal-secret 이 설정되지 않았습니다. .env 에 AI_INTERNAL_SECRET 을 추가하세요. " +
                    "(AI 서버가 X-Internal-Secret 헤더로 이 값을 요구합니다)");
        }
    }
}

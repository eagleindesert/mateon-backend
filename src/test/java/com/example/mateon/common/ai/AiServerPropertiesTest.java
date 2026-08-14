package com.example.mateon.common.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설정 홀더 중 유일하게 <b>진짜 로직</b>이 있는 곳: 시크릿 누락을 부팅 시점에 잡는 fail-fast.
 *
 * <p>이게 왜 손으로 짠 검사인지가 요점이다. {@code @ConfigurationProperties} 바인딩은
 * {@code @Value} 와 달리 해결하지 못한 플레이스홀더에서 예외를 내지 않고 원문
 * {@code "${AI_INTERNAL_SECRET}"} 을 <b>문자열 그대로</b> 넣는다. 그래서
 * <ul>
 *   <li>기본값을 안 주는 것만으로는 부팅이 실패하지 않고,</li>
 *   <li>{@code @NotBlank} 로도 안 걸린다 (빈 문자열이 아니다).</li>
 * </ul>
 * 그 리터럴이 헤더로 나가면 AI 서버가 401 로 거절하고, 우리 로그에는 502 만 남아 원인 파악에
 * 한참 걸린다. 그래서 {@code "${"} 로 시작하는 값까지 명시적으로 막는다 — 이 조건은 "이상해
 * 보인다"는 이유로 지워지기 딱 좋아서 테스트로 붙잡아 둔다.
 *
 * <p>임베딩 차원 기본값 1536 도 함께 고정한다. DB 컬럼이 {@code vector(1536)} 이라 이 값이
 * 어긋나면 저장 시점에 Postgres 가 거절한다.
 */
class AiServerPropertiesTest {

    @Test
    @DisplayName("시크릿이 정상이면 통과한다")
    void validSecretPasses() {
        assertThatCode(() -> validate(properties("real-secret-value")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("시크릿이 없으면 부팅을 막는다")
    void nullSecretFailsFast() {
        assertThatThrownBy(() -> validate(properties(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_INTERNAL_SECRET");
    }

    @Test
    @DisplayName("빈 문자열·공백도 막는다")
    void blankSecretFailsFast() {
        assertThatThrownBy(() -> validate(properties("")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate(properties("   ")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("해결되지 않은 플레이스홀더 원문도 막는다 — @NotBlank 로는 잡히지 않는 경우다")
    void unresolvedPlaceholderFailsFast() {
        assertThatThrownBy(() -> validate(properties("${AI_INTERNAL_SECRET}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".env");
    }

    @Test
    @DisplayName("임베딩 차원 기본값은 1536 이다 (DB 컬럼이 vector(1536) 이다)")
    void defaultEmbeddingDimensionMatchesSchema() {
        assertThat(new AiServerProperties().getEmbeddingDimension()).isEqualTo(1536);
    }

    @Test
    @DisplayName("연결은 짧게(3초), 응답은 넉넉히(60초) — LLM 호출이라 둘의 성격이 다르다")
    void timeoutDefaults() {
        AiServerProperties properties = new AiServerProperties();

        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getSessionTtl()).isEqualTo(Duration.ofHours(24));
    }

    private AiServerProperties properties(String secret) {
        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl("http://ai.test:8001");
        properties.setInternalSecret(secret);
        return properties;
    }

    /**
     * {@code @PostConstruct} 메서드가 package-private 이라 리플렉션으로 부른다.
     * (테스트가 같은 패키지에 있어 직접 호출도 가능하지만, 스프링이 부팅 때 부르는 경로와
     * 같은 방식으로 두어 접근제어자가 바뀌어도 테스트가 따라가지 않게 한다.)
     */
    private void validate(AiServerProperties properties) {
        ReflectionTestUtils.invokeMethod(properties, "validateInternalSecret");
    }
}

package com.example.mateon.support;

import com.example.mateon.common.ai.AiCallTemplate;
import com.example.mateon.common.ai.AiRestTemplateConfig;
import com.example.mateon.common.ai.AiServerProperties;
import org.junit.jupiter.api.Assumptions;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * {@code *LiveTest} 가 ai-stub 에 붙을 때 쓰는 하네스.
 *
 * <p>
 * 베이스 클래스가 아니라 정적 헬퍼인 이유: 저장까지 보는 LiveTest 는
 * {@link IntegrationTestBase} 를 상속해야 하는데, 하네스도 베이스 클래스면 단일 상속에서
 * 충돌한다. 정적 헬퍼는 상속 계층에 끼어들지 않아 DB 없는 테스트와 있는 테스트가 똑같이 쓴다.
 *
 * <p>
 * 게이트가 두 겹인 이유. 이 프로젝트에는 CI 가 없어 {@code ./gradlew test} 는 사람이 손으로
 * 돌리고 IDE 는 클래스를 직접 실행한다. build.gradle 의 이름 필터는 그 두 경로를 못 막으므로
 * 테스트가 스스로 꺼져야 한다. 그런데 환경변수 이름만으로는 <b>실서버 오조준</b>을 막지
 * 못한다 — 그래서 {@code GET /__stub} 으로 상대가 정말 우리 스텁인지 확인하고, 아니면
 * 아무 호출도 하지 않고 건너뛴다.
 *
 * <p>
 * {@code AI_BASE_URL} 을 재사용하지 않는 것도 같은 이유다. 개발하다 보면 셸에 그 값이 떠
 * 있게 되고, 그러면 테스트가 의도 없이 실서버를 때린다. 전용 변수를 일부러 세팅하는 행위
 * 자체가 동의여야 한다.
 */
public final class AiStubSupport {

    private static final String BASE_URL_ENV = "AI_STUB_BASE_URL";
    private static final String SECRET_ENV = "AI_STUB_SECRET";

    /**
     * 스텁은 {@code -ExpectedSecret} 없이 띄우면 시크릿을 검증하지 않는다. 그래서 기본값이
     * 있어도 되고, 값 자체에는 의미가 없다.
     */
    private static final String DEFAULT_SECRET = "stub-secret";

    private static final String IDENTITY_PATH = "/__stub";
    private static final String IDENTITY_MARKER = "mateon-ai-stub";

    /**
     * 신원 확인 결과 캐시. null 이면 통과, 문자열이면 그게 건너뛴 사유다.
     * 클래스마다 다시 물으면 스텁 콘솔이 확인 로그로 덮인다.
     */
    private static String skipReason;
    private static boolean probed;

    private AiStubSupport() {
    }

    /**
     * 스텁이 떠 있고 그게 정말 우리 스텁이면 통과, 아니면 테스트를 건너뛴다.
     *
     * <p>
     * {@code @EnabledIfEnvironmentVariable} 대신 이 방식을 쓰는 이유는 애노테이션 상속에
     * 기대지 않고 두 게이트를 한자리에서 처리하기 위해서다. 건너뛴 사유가 메시지로 남는 것도
     * 중요하다 — "0건 실행"과 "스텁을 안 띄웠다"를 구분할 수 있어야 한다.
     */
    public static void assumeStubAvailable() {
        if (!probed) {
            skipReason = probe();
            probed = true;
            if (skipReason != null) {
                // Assumptions.abort 의 메시지는 JUnit XML 의 <skipped/> 에 안 실려서 Gradle
                // 리포트만 봐서는 "왜 0건인지"를 알 수 없다. 한 번만 직접 찍는다.
                System.out.println("[LiveTest 건너뜀] " + skipReason);
            }
        }
        if (skipReason != null) {
            Assumptions.abort(skipReason);
        }
    }

    private static String probe() {
        String baseUrl = System.getenv(BASE_URL_ENV);
        if (!StringUtils.hasText(baseUrl)) {
            return BASE_URL_ENV + " 가 없습니다. ai-stub 을 띄우고 이 변수를 지정하세요 "
              + "(scripts/test/debug/ai-stub/README.md 참고).";
        }

        String body;
        try {
            body = probeTemplate().getForObject(baseUrl + IDENTITY_PATH, String.class);
        } catch (Exception e) {
            // 여기로 오는 경우는 둘이다 — 스텁이 안 떠 있거나, 주소가 스텁이 아니거나.
            // 후자면 대개 404 다 (실서버든 아무 웹서버든 이 경로를 모른다). 어느 쪽이든 거절한다.
            return baseUrl + IDENTITY_PATH + " 에서 스텁 신원을 확인하지 못했습니다. "
              + "ai-stub 이 떠 있는지, 주소가 그 스텁을 가리키는지 확인하세요. "
              + "(" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }

        if (body == null || !body.contains(IDENTITY_MARKER)) {
            return baseUrl + " 는 ai-stub 이 아닙니다 (" + IDENTITY_PATH + " 가 "
              + IDENTITY_MARKER + " 를 돌려주지 않음). LiveTest 는 실서버를 상대로 돌리지 않습니다.";
        }
        return null;
    }

    /**
     * 신원 확인 전용 RestTemplate. 타임아웃이 짧다 — 여기서 오래 기다릴 이유가 없고,
     * 주소가 틀렸을 때 60초짜리 운영 타임아웃으로 매달리면 곤란하다.
     */
    private static RestTemplate probeTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        return new RestTemplate(factory);
    }

    /**
     * 스텁을 가리키는 설정. {@code validateInternalSecret()} 은 @PostConstruct 라 이 경로에선
     * 돌지 않는다 (스프링이 만든 빈이 아니다).
     */
    public static AiServerProperties properties() {
        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl(System.getenv(BASE_URL_ENV));

        String secret = System.getenv(SECRET_ENV);
        properties.setInternalSecret(StringUtils.hasText(secret) ? secret : DEFAULT_SECRET);
        return properties;
    }

    /**
     * 운영과 같은 RestTemplate.
     *
     * <p>
     * 손으로 {@code new RestTemplate()} 하지 않고 운영 설정 클래스를 그대로 부르는 게 핵심이다.
     * 타임아웃도 메시지 컨버터도 운영과 달라지면 "실제로 이렇게 역직렬화된다"는 검증의 근거가
     * 사라진다. 이 프로젝트는 Jackson 2 와 3 이 동시에 클래스패스에 있어 어느 컨버터가
     * 잡히는지가 특히 손으로 재현하기 어렵다.
     */
    public static RestTemplate aiRestTemplate() {
        return new AiRestTemplateConfig(properties()).aiRestTemplate();
    }

    public static AiCallTemplate aiCallTemplate() {
        return new AiCallTemplate(aiRestTemplate(), properties());
    }
}

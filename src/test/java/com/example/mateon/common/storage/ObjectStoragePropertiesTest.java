package com.example.mateon.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 객체 저장소 설정의 fail-fast 검사.
 *
 * <p>여기서 가장 중요한 건 <b>{@code "${OCI_BUCKET}"} 같은 미치환 플레이스홀더를 값으로
 * 치지 않는 것</b>이다. {@code .env} 에 키가 없으면 스프링이 {@code ${...}} 문자열을 그대로
 * 주입하는데, 문자열로는 비어 있지 않아 평범한 null/공백 검사를 통과한다. 그러면 앱은 정상
 * 기동하고, 몇 시간 뒤 사용자가 이미지를 올리는 순간 {@code "${OCI_BUCKET}"} 이라는 이름의
 * 버킷을 찾다가 실패한다 — 그때의 에러 메시지는 설정 누락과 아무 관련 없어 보인다.
 *
 * <p>{@code @PostConstruct} 라 앱 기동 시 터지게 하는 것이 목적이고, 메시지에 <b>어떤 키가
 * 빠졌는지</b>가 나와야 그 자리에서 고칠 수 있다. 그래서 "터진다"가 아니라 "무엇이 빠졌다고
 * 말하는가"까지 단언한다.
 *
 * <p>{@code AiServerProperties.validateInternalSecret()} 과 같은 판단이다 — 두 곳뿐인 진짜
 * fail-fast 로직이라 설정 클래스 중 이 둘만 테스트를 둔다.
 */
class ObjectStoragePropertiesTest {

    private ObjectStorageProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        properties.setNamespace("ns");
        properties.setRegion("ap-chuncheon-1");
        properties.setBucket("mateon-images");
        properties.setAccessKey("access");
        properties.setSecretKey("secret");
    }

    @Test
    @DisplayName("다섯 값이 모두 채워져 있으면 통과한다")
    void allPresent() {
        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("빠진 값이 있으면 기동을 막고, 어떤 환경변수인지 이름을 알려준다")
    void namesMissingKey() {
        properties.setBucket(null);

        assertThatThrownBy(this::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCI_BUCKET")
                .hasMessageContaining(".env");
    }

    @Test
    @DisplayName("공백뿐인 값도 없는 것으로 친다")
    void blankCountsAsMissing() {
        properties.setAccessKey("   ");

        assertThatThrownBy(this::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCI_S3_ACCESS_KEY");
    }

    /**
     * {@code .env} 에 키가 없을 때 스프링이 넣어 주는 값이다. 문자열로는 비어 있지 않아
     * null/공백 검사만으로는 잡히지 않는다.
     */
    @Test
    @DisplayName("치환되지 않은 ${...} 플레이스홀더는 값이 아니다 — 이게 이 검사의 존재 이유다")
    void unresolvedPlaceholderIsNotAValue() {
        properties.setNamespace("${OCI_NAMESPACE}");

        assertThatThrownBy(this::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCI_NAMESPACE");
    }

    @Test
    @DisplayName("여러 개가 빠지면 전부 한 번에 알려준다 (고치고 다시 뜨고를 반복하지 않게)")
    void listsAllMissingKeys() {
        properties.setRegion(null);
        properties.setSecretKey("");

        assertThatThrownBy(this::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCI_REGION")
                .hasMessageContaining("OCI_S3_SECRET_KEY");
    }

    @Test
    @DisplayName("업로드 크기 상한은 검사 대상이 아니다 (기본 0 이어도 기동한다)")
    void maxBytesIsNotValidated() {
        assertThat(properties.getMaxBytes().toBytes()).isZero();

        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    /** {@code @PostConstruct} 메서드가 package-private 이라 직접 부른다. */
    private void validate() {
        ReflectionTestUtils.invokeMethod(properties, "validate");
    }
}

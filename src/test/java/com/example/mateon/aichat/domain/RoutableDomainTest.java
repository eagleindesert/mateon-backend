package com.example.mateon.aichat.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 위임 가능 여부가 엔드포인트 null 과 같다는 계약을 고정한다.
 *
 * <p>
 * {@code UNCLEAR}/{@code OUT_OF_SCOPE} 는 작업 행을 만들지 않는다. 게이트웨이가
 * {@link RoutableDomain#isDelegatable()} 로 그 분기를 가르므로, 상수만 늘리고 이 판정을
 * 잊으면 위임할 곳 없는 판정에도 도메인 서비스를 부르게 된다.
 */
class RoutableDomainTest {

    @Test
    @DisplayName("엔드포인트가 있는 상수만 위임한다")
    void onlyDomainsWithEndpointAreDelegatable() {
        assertThat(RoutableDomain.MATCHING_INTENT.isDelegatable()).isTrue();
        assertThat(RoutableDomain.UNCLEAR.isDelegatable()).isFalse();
        assertThat(RoutableDomain.OUT_OF_SCOPE.isDelegatable()).isFalse();
    }
}

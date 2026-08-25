package com.example.mateon.aigateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 게이트웨이 라우터 설정. (common/ai/AiServerProperties 와 같은 형태)
 *
 * <p>
 * 여기에는 시크릿 검증(@PostConstruct)이 없다 — AiServerProperties 와 다른 점이다. AI 서버
 * 시크릿은 없으면 기능이 아예 못 도니 부팅을 막는 게 맞지만, 라우터는 <b>실패해도 기존 동작으로
 * 폴백</b>하도록 설계돼 있다. 키가 없다고 앱을 못 뜨게 하면 그 폴백을 시험할 수조차 없고,
 * 게이트웨이 하나 때문에 서비스 전체가 안 뜨는 건 도입 전보다 나쁘다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "airouter")
public class AiRouterProperties {

    /**
     * 라우터 사용 여부. false 면 분류를 건너뛰고 곧장 매칭 의도 추출로 통과시킨다
     * (= 게이트웨이 도입 전 동작). 로컬 개발과 장애 시 우회로.
     */
    private boolean enabled = true;
}

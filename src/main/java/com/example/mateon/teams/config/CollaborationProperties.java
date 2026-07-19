package com.example.mateon.teams.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 협업 온도 관련 운영 파라미터. (AiServerProperties 와 같은 형태의 슬라이스 로컬 설정)
 *
 * <p>온도 공식의 계수(C, K)는 여기 없다 — CollaborationTemperatureCalculator 의 상수다.
 * 계수를 바꾸면 이미 저장된 온도를 전부 재계산해야 하므로, 재배포 없이 바꿀 수 있는 값처럼
 * 보이면 안 된다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "collaboration")
public class CollaborationProperties {

    /** 종료 후 평가를 받는 기간(일). 지나면 제출이 거부된다. */
    private int reviewWindowDays = 14;

    /**
     * 자동 종료 배치 cron. 매일 새벽 3시.
     *
     * <p>공모전 마감일(events.end_date)이 지난 팀을 종료 처리한다. 하루 한 번이면 충분하다 —
     * 평가 기간이 2주라 몇 시간의 지연은 의미가 없다.
     */
    private String autoCompleteCron = "0 0 3 * * *";
}

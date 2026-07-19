package com.example.mateon.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled 활성화 (AsyncConfig 와 같은 이유로 config/ 에 둔다).
 *
 * <p>현재 사용처: 공모전 마감일이 지난 팀의 자동 종료(TeamCompletionScheduler) —
 * 팀장이 '종료' 버튼을 안 눌러도 협업 온도 평가가 열리게 하는 폴백이다.
 *
 * <p><b>주의: 단일 인스턴스 전제다.</b> 여러 대로 늘리면 모든 인스턴스가 같은 배치를 동시에 돌린다.
 * 자동 종료는 멱등이라(이미 ended_at 이 있으면 건너뜀) 중복 실행이 데이터를 망가뜨리진 않지만,
 * 알림이 중복 발송될 수 있다. 스케일아웃 시점에 ShedLock 같은 잠금을 도입해야 한다.
 * (같은 제약이 notification/repository/EmitterRepository 에도 있다.)
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

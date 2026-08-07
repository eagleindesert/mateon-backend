package com.example.mateon.matching.dto.snapshot;

import com.example.mateon.user.domain.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 추천된 유저 1명의 표시 정보. {@link TeamDisplayInfo} 의 유저판이다.
 *
 * <p>협업 온도는 평가 건수와 무관하게 항상 값이 있다. 아직 평가를 못 받은 유저는 집계 행이 없는데,
 * 그건 0건과 같은 상태이므로 호출부가 기준점(36.5)으로 채운다 (V28 주석 참고).
 */
@Getter
@RequiredArgsConstructor
public class UserDisplayInfo {

    private final User user;

    /** 협업 온도. 평가가 0건이면 기준점 36.5 다 (항상 값이 있다). */
    private final BigDecimal collaborationTemperature;
}

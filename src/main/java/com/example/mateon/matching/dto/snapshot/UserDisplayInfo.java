package com.example.mateon.matching.dto.snapshot;

import com.example.mateon.user.domain.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 추천된 유저 1명의 표시 정보. {@link TeamDisplayInfo} 의 유저판이다.
 *
 * <p>협업 온도는 평가를 2건 이상 받아야 값이 생긴다 — 없으면 null 이고, 그건 "0도"가 아니라
 * "비공개"다 (V13 주석 참고). 프론트도 그렇게 다뤄야 한다.
 */
@Getter
@RequiredArgsConstructor
public class UserDisplayInfo {

    private final User user;

    /** 협업 온도. 평가 표본이 부족하면 null(비공개). */
    private final BigDecimal collaborationTemperature;
}

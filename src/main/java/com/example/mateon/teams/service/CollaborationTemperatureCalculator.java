package com.example.mateon.teams.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 협업 온도 계산. 스프링 의존이 없는 순수 계산기다 (EventMatchingService 와 같은 방식 —
 * 이래야 단위 테스트가 컨텍스트 없이 돈다).
 *
 * <p>
 * 온도는 두 축의 곱으로 만든다.
 *
 * <ol>
 * <li><b>품질 q</b> — 베이지안 평균으로 수축시킨 평점. 표본이 적을 때 산술평균은 분산이 폭발한다.
 * 평가 2건이 우연히 5.0 인 유저가 100건에 걸쳐 4.6 을 지킨 유저 위에 서면 안 된다.
 * 중립(3.0)을 사전분포로 두고 가중치 C 만큼 끌어당긴다 (IMDb Top250 등이 쓰는 표준 기법).
 * <li><b>신뢰도 E</b> — 누적 건수의 포화 함수. 상한을 99 까지 열어 두면 품질만으로는 부족하다.
 * 3건 만점인 유저가 곧장 90점대에 가면 그 숫자는 아무것도 증명하지 못하고, 셀프 팀 결성
 * 몇 번으로 조작 가능한 값이 된다. "얼마나 검증됐는가"를 별도 축으로 분리한다.
 * </ol>
 *
 * <p>
 * 기준 36.5 는 당근마켓 매너온도가 국내에 안착시킨 스케일이라 별도 설명이 필요 없다.
 * 기준점이 0~99 구간의 아래쪽에 있어 위로 62.5, 아래로 36.5 의 여유가 남는다. 각 방향은 자기
 * 여유분에 같은 비율을 적용하므로 <b>비율로는 대칭</b>이다 — 다만 여유분 자체가 다르니 절대 온도로는
 * 상승 폭이 늘 더 크다 (30건 만점 +32.1 / 30건 최저 -18.8). 이건 기준점 위치에서 따라 나오는
 * 결과이지 부정 평가를 관대하게 본다는 뜻이 아니다.
 *
 * <p>
 * 표본이 적어도 값을 감추지 않는다. 예전에는 2건 미만이면 비공개(null)였다 — 온도는 평점에서
 * 정확히 역산되므로 2인 팀에서 평가가 1건이면 상대가 몇 점을 줬는지 특정된다. 그럼에도 화면에서
 * 온도가 빈 칸으로 남는 쪽이 제품상 더 나쁘다고 보고 걷어냈다. 1건이 움직이는 폭은 E(1)=1/21 이라
 * ±0.5 도 안쪽이지만, 폭이 작은 것과 역산이 막히는 것은 다른 얘기다. 태그나 코멘트처럼 서술형
 * 정보를 온도에 붙이게 되면 그때는 임계값을 다시 세워야 한다.
 *
 * <p>
 * 부정 신호에 가중치를 더 주는 방식(negativity bias)도 검토했으나 v1 에서는 뺐다. 표본이 얇은
 * 초기에 하락만 증폭되면 평가 한 건의 실수나 보복이 회복하기 어려운 낙인이 된다. 데이터가 쌓여
 * 실제 평점 분포를 본 뒤 도입 여부를 정한다.
 *
 * <p>
 * B < 5, E(n) < 1 이 항상 성립하므로 99 에는 점근적으로만 접근한다 (현실적인 건수에서는
 * 근처에도 가지 않는다 — 1000건 만점이 97.5). 만점이 존재하지 않는 편이 평판 지표로서 건전하다.
 */
public final class CollaborationTemperatureCalculator {

    /**
     * 사전분포 평균. 5점 척도의 중립값.
     */
    public static final double NEUTRAL_RATING = 3.0;

    /**
     * 품질 수축 가중치 C. 팀이 보통 3~5명이라 첫 프로젝트에서 받는 평가가 2~4건인데,
     * 5 로 두면 첫 팀 직후 자기 점수 반영이 30~45%, 두 번째 팀을 마치면 50% 를 넘는다.
     * "한 팀만으로는 확정되지 않지만 두 팀이면 유의미해진다"를 수치로 옮긴 값.
     */
    public static final double PRIOR_WEIGHT = 5.0;

    /**
     * 신뢰도 포화 상수 K. 약 5~6개 팀(≈20건)을 마쳐야 잠재 상승분의 절반에 도달한다.
     * 대학생 사용자 기준 1~2년치 활동량이라, 상위 온도가 희소하게 유지된다.
     */
    public static final double EVIDENCE_HALF = 20.0;

    public static final double BASE_TEMP = 36.5;
    public static final double MAX_TEMP = 99.0;
    public static final double MIN_TEMP = 0.0;

    /**
     * 평가를 한 번도 안 받은 유저의 온도. 공식에 n=0 을 넣으면 E(0)=0 이라 기준점이 그대로 나오므로
     * 예외 분기가 아니라 공식의 자연스러운 결과다.
     *
     * <p>
     * 집계 행 자체가 없는 유저에게도 이 값을 내려보낸다 — 행이 없는 것과 0건인 것은 같은 상태다.
     */
    public static final BigDecimal INITIAL = temperature(0, 0);

    private CollaborationTemperatureCalculator() {
    }

    /**
     * @param reviewCount 받은 평가 수 (0 이상)
     * @param ratingSum 받은 평점의 합 (각 1~5)
     * @return 소수 첫째 자리까지의 온도. 평가가 없으면 기준점({@link #INITIAL})이며 {@code null} 이
     * 아니다 — 표본이 적다는 이유로 감추지 않는다 (클래스 주석 참고).
     */
    public static BigDecimal temperature(int reviewCount, int ratingSum) {
        // 품질: 중립으로 수축시킨 평균을 (-1, +1) 로 정규화.
        double bayesian = (PRIOR_WEIGHT * NEUTRAL_RATING + ratingSum) / (PRIOR_WEIGHT + reviewCount);
        double quality = (bayesian - NEUTRAL_RATING) / 2.0;

        // 신뢰도: 건수가 쌓일수록 1 에 접근하되 결코 도달하지 않는다.
        double evidence = reviewCount / (reviewCount + EVIDENCE_HALF);

        // 위아래로 남은 여유분이 달라 방향에 따라 스케일이 다르다.
        double headroom = quality >= 0 ? (MAX_TEMP - BASE_TEMP) : (BASE_TEMP - MIN_TEMP);
        double temperature = BASE_TEMP + headroom * quality * evidence;

        // 위 성질상 범위를 벗어날 수 없지만, 계수를 조정했을 때 조용히 깨지지 않도록 붙잡아 둔다.
        temperature = Math.max(MIN_TEMP, Math.min(MAX_TEMP, temperature));

        return BigDecimal.valueOf(temperature).setScale(1, RoundingMode.HALF_UP);
    }
}

-- V28: 협업 온도를 표본 크기와 무관하게 항상 노출한다.
--
-- V13 은 평가 2건 미만이면 temperature 를 NULL(비공개)로 뒀다. 통계가 아니라 익명성 때문이었다 —
-- 온도는 평점에서 정확히 역산되므로 2인 팀에서 1건이면 상대가 몇 점을 줬는지 특정된다.
-- 그럼에도 화면에서 온도가 빈 칸으로 남는 쪽이 제품상 더 나쁘다고 보고 임계값을 걷어냈다.
-- (판단 근거는 CollaborationTemperatureCalculator 클래스 주석에 남겨 뒀다.)

-- 1) 비공개로 남아 있던 행을 실제 온도로 채운다.
--
--    공식 계수는 원래 Java 상수 한 곳에만 두는 게 원칙이지만, 백필은 SQL 에서 계산할 수밖에 없다.
--    이 파일은 한 번 적용되고 다시 바뀌지 않으므로 여기 박힌 숫자는 "그때의 공식"으로 고정된다 —
--    이후 계수를 바꾸면 이 마이그레이션이 아니라 새 재집계 마이그레이션을 써야 한다.
--
--    C=5, 중립=3.0, K=20, 기준=36.5, 상한=99.0, 하한=0.0
UPDATE user_collaboration_scores s
SET temperature = round(
        36.5 + (CASE WHEN c.quality >= 0 THEN 99.0 - 36.5 ELSE 36.5 - 0.0 END)
             * c.quality
             * (s.review_count::numeric / (s.review_count + 20)),
        1)
FROM (SELECT user_id,
             ((5 * 3.0 + rating_sum) / (5 + review_count) - 3.0) / 2.0 AS quality
      FROM user_collaboration_scores) c
WHERE c.user_id = s.user_id
  AND s.temperature IS NULL;

-- 2) 이제 NULL 이 될 수 있는 경로가 없다. 스키마에서 막아 두면 임계값이 코드로 되살아나도
--    조용히 지나가지 않는다.
--
--    DEFAULT 는 평가 0건일 때의 온도 — E(0)=0 이라 기준점이 그대로 나온다.
ALTER TABLE user_collaboration_scores ALTER COLUMN temperature SET DEFAULT 36.5;
ALTER TABLE user_collaboration_scores ALTER COLUMN temperature SET NOT NULL;

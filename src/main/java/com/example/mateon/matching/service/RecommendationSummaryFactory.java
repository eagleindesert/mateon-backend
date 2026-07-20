package com.example.mateon.matching.service;

import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamEmbedding;
import com.example.mateon.user.domain.User;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /recommendations/reason 요청에 실을 세 문자열을 조립한다.
 *
 * <p>
 * AI 서버는 stateless 라 이유 생성 컨텍스트를 백엔드가 전부 보내야 하는데, 이 세 값에
 * 대응하는 컬럼은 <b>일부러 만들지 않았다</b> — 이미 있는 데이터에서 조립할 수 있기 때문이다.
 * 저장하는 건 AI 의 출력(reason)뿐이다 (V17 주석 참고).
 *
 * <p>
 * <b>왜 층을 내려가나.</b> 요약의 1순위는 {@code embedding_text} 다 — AI 가 임베딩 벡터를
 * 만들 때 실제로 모델에 넣은 원문이라 "이 유저/팀은 무엇인가"를 한 덩어리 자연어로 압축한 값이고,
 * 명세가 요구하는 요약과 성격이 정확히 같다. 그런데 이게 null 이라는 건 임베딩 갱신이 한 번도
 * 성공하지 못했다는 뜻이고, 그러면 같은 행의 정규화 메타데이터(recruiting_roles 등)도 대개
 * 함께 비어 있다 — 같은 AI 응답에서 온 값들이라 운명을 공유한다. 그래서 2층으로는 부족하고,
 * <b>항상 존재하는 원본 행(teams/users)</b>을 마지막 층으로 깐다.
 *
 * <p>
 * DB 도 AI 도 만지지 않는 순수 함수라 빈으로 만들지 않는다. 덕분에 단위 테스트도 쉽다.
 */
public final class RecommendationSummaryFactory {

    /**
     * embedding_text 가 조각을 잇는 방식과 같은 구분자.
     */
    private static final String PART_DELIMITER = " / ";
    private static final String LIST_DELIMITER = ", ";

    private RecommendationSummaryFactory() {
    }

    // ── 유저 요약 ──────────────────────────────────────────────────────────────
    /**
     * 유저 한 명을 자연어 한 덩어리로. 방향에 따라 candidate_summary 도 target_summary 도 된다.
     *
     * @param slot 의도 추출 결과. 추천 후보가 되려면 존재해야 하므로 null 이 아니다.
     * @param user 폴백 3층용 원본 행.
     * @return 항상 non-null. 조립할 게 하나도 없으면 빈 문자열 (AI 명세가 허용한다).
     */
    public static String userSummary(MatchingIntentSlot slot, User user) {
        // 1층: 정상 경로. 거의 항상 여기서 끝난다.
        if (isPresent(slot.getEmbeddingText())) {
            return slot.getEmbeddingText();
        }

        // 2층: 슬롯의 정규화 값들.
        // desiredRoles 는 AI 코드("BE")고 embedding_text 는 이걸 "백엔드"로 풀어 쓰지만,
        // 코드를 한글로 되돌리는 매핑 테이블은 만들지 않는다 — 그 어휘는 AI 쪽 스펙이라
        // 복제하면 양쪽이 어긋나기 시작한다(MatchingIntentSlot 이 enum 을 금지한 것과 같은 이유).
        // LLM 은 "BE" 도 충분히 읽어낸다.
        String fromSlot = joinParts(
          joinList(slot.getDesiredRoles()),
          joinList(slot.getSkills()),
          joinList(slot.getInterests()),
          slot.getActivityGoal(),
          slot.getActivityStyle(),
          slot.getExperienceLevel());
        if (fromSlot != null) {
            return fromSlot;
        }

        // 3층: users 행. 의도와 무관한 정적 프로필이라 이유 품질은 떨어지지만 항상 있다.
        String fromProfile = joinParts(
          user.getMajor(),
          user.getGrade(),
          joinList(interestJobs(user)),
          user.getTagline());

        return fromProfile != null ? fromProfile : "";
    }

    /**
     * 관심 직무 3칸 중 채워진 것만. 자유 입력이라 중복 제거는 하지 않는다.
     */
    private static List<String> interestJobs(User user) {
        List<String> jobs = new ArrayList<>(3);
        addIfPresent(jobs, user.getInterestJobPrimary());
        addIfPresent(jobs, user.getInterestJobSecondary());
        addIfPresent(jobs, user.getInterestJobTertiary());
        return jobs;
    }

    // ── 팀 요약 ────────────────────────────────────────────────────────────────
    /**
     * 팀 하나를 자연어 한 덩어리로.
     *
     * @param embedding 팀 임베딩 행. 갱신을 한 번도 시도하지 않았으면 <b>행 자체가 없을 수</b>
     * 있어 null 을 허용한다.
     * @param team 폴백 3층용 원본 행.
     * @return 항상 non-null. 조립할 게 하나도 없으면 빈 문자열.
     */
    public static String teamSummary(TeamEmbedding embedding, Team team) {
        if (embedding != null) {
            // 1층
            if (isPresent(embedding.getEmbeddingText())) {
                return embedding.getEmbeddingText();
            }

            // 2층: AI 정규화 메타데이터. 역할/스킬은 라벨을 붙여야 나열이 뭘 뜻하는지 전달된다.
            String fromMetadata = joinParts(
              prefixed("모집 역할: ", joinList(embedding.getRecruitingRoles())),
              prefixed("요구 스킬: ", joinList(embedding.getRequiredSkills())),
              embedding.getActivityGoal(),
              embedding.getActivityStyle(),
              embedding.getActivityIntensity(),
              // false 와 null(AI 가 못 읽어냄)은 다르고, 둘 다 문장에 넣지 않는다.
              // "초보자 환영 아님" 같은 부정 문구를 만들면 LLM 이 없는 배타성을 지어낸다.
              Boolean.TRUE.equals(embedding.getBeginnerFriendly()) ? "초보자 환영" : null);
            if (fromMetadata != null) {
                return fromMetadata;
            }
        }

        // 3층: teams 행. role 은 한글 자유문자열("백엔드 개발자")이라 2층의 정규화 코드("BE")와
        // 어휘가 다르지만 상관없다 — 이유 생성은 룰 매칭이 아니라 LLM 이 읽는 자연어다.
        String fromTeam = joinParts(
          team.getTitle(),
          prefixed("모집 역할: ", joinList(team.getRole())),
          prefixed("요구 스킬: ", joinList(team.getRequiredSkills())),
          team.getCharacteristic(),
          team.getPromotionText());

        return fromTeam != null ? fromTeam : "";
    }

    // ── score_context ─────────────────────────────────────────────────────────
    /**
     * 점수 구성 서술. 자유 문자열이라 형식 제약이 없다 — AI 는 이걸 필드별로 읽지 않고 프롬프트에
     * 그대로 이어붙인다.
     *
     * @param score AI 가 매긴 적합도. 0~1 스케일이라 백분율로 풀어 쓴다.
     * @param rankNo 그 추천 호출에서의 순위 (1부터).
     * @param label AI 가 만든 짧은 근거 문구. 없으면 점수만 남는다.
     */
    public static String scoreContext(double score, int rankNo, String label) {
        String base = "적합도 %.0f점(추천 %d위)".formatted(score * 100, rankNo);
        return isPresent(label) ? base + ". " + label : base;
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────
    /**
     * 비어있지 않은 조각만 이어 붙인다.
     *
     * @return 전부 비면 <b>빈 문자열이 아니라 null</b>. 층 사이의 폴백 판정을 {@code == null}
     * 하나로 통일하기 위해서다.
     */
    private static String joinParts(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (!isPresent(part)) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append(PART_DELIMITER);
            }
            joined.append(part.strip());
        }
        return joined.isEmpty() ? null : joined.toString();
    }

    /**
     * 리스트를 ", " 로. null/빈 리스트/원소가 전부 빈 값이면 null.
     */
    private static String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (!isPresent(value)) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append(LIST_DELIMITER);
            }
            joined.append(value.strip());
        }
        return joined.isEmpty() ? null : joined.toString();
    }

    /**
     * 값이 있을 때만 라벨을 붙인다. 없으면 null 이라 joinParts 가 통째로 건너뛴다.
     */
    private static String prefixed(String label, String value) {
        return value != null ? label + value : null;
    }

    private static void addIfPresent(List<String> target, String value) {
        if (isPresent(value)) {
            target.add(value.strip());
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}

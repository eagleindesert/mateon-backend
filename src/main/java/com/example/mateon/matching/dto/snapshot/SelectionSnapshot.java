package com.example.mateon.matching.dto.snapshot;

import com.example.mateon.matching.domain.SelectionDirection;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 선택 이벤트를 조립하는 데 필요한 값들. 조회 TX 가 커밋된 뒤 TX 밖에서 FastAPI 를 호출할 때
 * 넘긴다 ({@link RecommendationSnapshot} 과 같은 역할).
 *
 * <p>
 * 다른 스냅샷과 달리 <b>엔티티를 하나도 들고 있지 않다.</b> 여기 담긴 값은 전부 AI 로 그대로
 * 나가는 것들이라 지연 로딩 걱정이 아예 없는 편이 낫다.
 *
 * <p>
 * {@code componentScores} 를 Map 이 아니라 문자열로 들고 다니는 게 핵심이다 — 명세가 "값을
 * 재계산하거나 이름을 바꾸지 않고" 보관·전송하기를 요구하므로, DB 에 저장된 원문 JSON 이
 * 파싱을 거치지 않고 요청 본문까지 그대로 흘러가야 한다.
 */
@Getter
@RequiredArgsConstructor
public class SelectionSnapshot {

    private final SelectionDirection direction;

    /** 실제로 선택된 팀 ID(USER_TO_TEAM) 또는 유저 ID(TEAM_TO_USER). */
    private final Long selectedCandidateId;

    /**
     * 선택된 후보의 추천 아이템 id. 이걸로 selected_at 을 찍는다
     * (AI 호출 성패와 무관하게 우리 DB 에는 선택이 남아야 한다).
     */
    private final Long selectedItemId;

    /**
     * 선택 주체의 클러스터 계산용 원본 필드. 방향별로 키가 다르다
     * (USER_TO_TEAM: desired_roles/experience_level, TEAM_TO_USER: recruiting_roles/contest_field).
     *
     * <p>
     * 키가 AI 명세의 snake_case 그대로라 여기서는 Map 으로 둔다 — 방향마다 DTO 를 만들면
     * 필드가 두 개뿐인 클래스가 둘 더 생길 뿐이다.
     */
    private final Map<String, Object> chooserFields;

    /** 선택 당시 화면에 노출됐던 추천 결과 전체 (순위 오름차순). */
    private final List<ShownCandidate> shownCandidates;

    @Getter
    @RequiredArgsConstructor
    public static class ShownCandidate {

        private final Long candidateId;

        private final double totalScore;

        /**
         * 추천 시점의 component_scores 원문 JSON. AI 가 안 줬던 추천이면 null.
         */
        private final String componentScores;
    }
}

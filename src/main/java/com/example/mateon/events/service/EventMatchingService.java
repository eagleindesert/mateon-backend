package com.example.mateon.events.service;

import com.example.mateon.events.models.Event;
import com.example.mateon.user.domain.User;
import org.springframework.stereotype.Service;

@Service
public class EventMatchingService {

    // 점수 가중치 상수
    private static final int SCORE_INTEREST_JOB_PRIMARY = 30;
    private static final int SCORE_INTEREST_JOB_SECONDARY = 20;
    private static final int SCORE_INTEREST_JOB_TERTIARY = 10;
    private static final int SCORE_MAJOR = 15;
    private static final int SCORE_COLLEGE = 10;
    private static final int SCORE_CAMPUS = 5;
    // 대상 대학교 일치. 단과대(10점)와 같은 무게로 둔다 — 둘 다 '소속이 맞는가'를 보는 축이다.
    private static final int SCORE_SCHOOL = 10;

    /**
     * 사용자 정보와 Event의 관련도 점수를 계산합니다.
     */
    @SuppressWarnings("deprecation") // 단과대/캠퍼스 대조는 폐기 예정이나, 기존 데이터가 거기 있어 유지한다.
    public int calculateRelevanceScore(User user, Event event) {
        int score = 0;

        // 1. 희망직무 매칭 (30점 + 20점 + 10점)
        if (user.getInterestJobPrimary() != null) {
            score += matchInterestJob(user.getInterestJobPrimary(), event, SCORE_INTEREST_JOB_PRIMARY);
        }
        if (user.getInterestJobSecondary() != null) {
            score += matchInterestJob(user.getInterestJobSecondary(), event, SCORE_INTEREST_JOB_SECONDARY);
        }
        if (user.getInterestJobTertiary() != null) {
            score += matchInterestJob(user.getInterestJobTertiary(), event, SCORE_INTEREST_JOB_TERTIARY);
        }

        // 2. 대상 대학교 매칭 (10점)
        // 자유 입력이라 표기가 흔들리고("단국대" vs "단국대학교"), 콤마로 여러 학교가 들어올 수도
        // 있어 부분일치로 본다. 필터가 아닌 가산점이라 느슨해도 손해가 작다.
        if (user.getSchool() != null && event.getTargetSchool() != null) {
            if (event.getTargetSchool().toLowerCase().contains(user.getSchool().trim().toLowerCase())) {
                score += SCORE_SCHOOL;
            }
        }

        // 3. 전공 매칭 (15점) [deprecated] target_colleges 는 target_school 로 대체 중이다.
        if (user.getMajor() != null && event.getTarget_colleges() != null) {
            if (event.getTarget_colleges().contains(user.getMajor())) {
                score += SCORE_MAJOR;
            }
        }

        // 4. 단과대 매칭 (10점) [deprecated] target_colleges 는 target_school 로 대체 중이다.
        if (user.getCollege() != null && event.getTarget_colleges() != null) {
            if (event.getTarget_colleges().contains(user.getCollege())) {
                score += SCORE_COLLEGE;
            }
        }

        // 5. 학교/캠퍼스 매칭 (5점) [deprecated] campus_scope 는 target_school 로 대체 중이다.
        String campusScope = event.getCampusScope();
        if (campusScope != null && !campusScope.isBlank()) {
            // campusScope 는 자유 입력이라 학교명("단국대학교")과 캠퍼스명("죽전") 중 무엇이든 들어올 수 있다.
            if (Event.CAMPUS_SCOPE_ALL.equalsIgnoreCase(campusScope.trim())
                    || matchesCampusScope(campusScope, user.getSchool())
                    || matchesCampusScope(campusScope, user.getCampus())) {
                score += SCORE_CAMPUS;
            }
        }

        return score;
    }

    /**
     * Event의 대상 범위와 사용자의 학교/캠퍼스가 같은지 검사합니다.
     * 자유 입력이라 표기가 흔들릴 수 있지만("죽전" vs "죽전캠퍼스"), 필터가 아닌 가산점이므로
     * 정확 일치로 둡니다.
     */
    private boolean matchesCampusScope(String campusScope, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return campusScope.trim().equalsIgnoreCase(value.trim());
    }

    /**
     * 희망직무 키워드와 Event의 관련도를 검사합니다.
     */
    private int matchInterestJob(String interestJob, Event event, int baseScore) {
        if (interestJob == null || interestJob.trim().isEmpty()) {
            return 0;
        }

        // Event의 제목, 설명, 요약 설명을 모두 합쳐서 검색 텍스트 생성
        String searchText = (event.getTitle() != null ? event.getTitle() : "") + " " +
                            (event.getDescription() != null ? event.getDescription() : "") + " " +
                            (event.getSummarizedDescription() != null ? event.getSummarizedDescription() : "");
        searchText = searchText.toLowerCase().trim();

        String interestJobLower = interestJob.toLowerCase().trim();

        // 정확히 일치하면 전체 점수 부여
        if (searchText.contains(interestJobLower)) {
            return baseScore;
        }

        // 키워드 부분 매칭 (예: "백엔드 개발자" -> "백엔드", "개발자")
        String[] keywords = extractKeywords(interestJobLower);
        if (keywords.length == 0) {
            return 0;
        }

        int matchCount = 0;
        for (String keyword : keywords) {
            if (keyword.length() >= 2 && searchText.contains(keyword)) {
                matchCount++;
            }
        }

        // 키워드가 일부라도 매칭되면 점수 부여 (부분 점수)
        if (matchCount > 0) {
            return (baseScore * matchCount) / keywords.length;
        }

        return 0;
    }

    /**
     * 키워드를 추출합니다 (공백 기준으로 분리)
     */
    private String[] extractKeywords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new String[0];
        }
        // 공백 기준으로 분리하고, 빈 문자열 제거
        return text.trim().split("\\s+");
    }
}


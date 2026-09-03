package com.example.mateon.events.service;

import com.example.mateon.events.models.Event;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventMatchingServiceTest {

    private static final int SCORE_CAMPUS = 5;
    private static final int SCORE_SCHOOL = 10;

    private final EventMatchingService service = new EventMatchingService();

    @Nested
    @DisplayName("학교/캠퍼스 매칭 가산점")
    class CampusScopeMatching {

        @Test
        @DisplayName("ALL 이면 학교와 무관하게 가산점을 준다")
        void allScopeAlwaysScores() {
            User user = user("서울대학교", "관악");

            assertThat(score(user, event(Event.CAMPUS_SCOPE_ALL))).isEqualTo(SCORE_CAMPUS);
        }

        @Test
        @DisplayName("학교명이 일치하면 가산점을 준다")
        void schoolNameMatches() {
            User user = user("단국대학교", "죽전");

            assertThat(score(user, event("단국대학교"))).isEqualTo(SCORE_CAMPUS);
        }

        @Test
        @DisplayName("캠퍼스명이 일치하면 가산점을 준다")
        void campusNameMatches() {
            User user = user("단국대학교", "죽전");

            assertThat(score(user, event("죽전"))).isEqualTo(SCORE_CAMPUS);
        }

        @Test
        @DisplayName("기존 JUKJEON 값도 그대로 매칭된다 (하위호환)")
        void legacyEnumValueStillMatches() {
            User user = user("단국대학교", "JUKJEON");

            assertThat(score(user, event("JUKJEON"))).isEqualTo(SCORE_CAMPUS);
        }

        @Test
        @DisplayName("앞뒤 공백과 대소문자는 무시한다")
        void trimsAndIgnoresCase() {
            User user = user("Dankook University", "jukjeon");

            assertThat(score(user, event("  JUKJEON  "))).isEqualTo(SCORE_CAMPUS);
        }

        @Test
        @DisplayName("다른 학교면 가산점이 없다")
        void differentSchoolScoresNothing() {
            User user = user("서울대학교", "관악");

            assertThat(score(user, event("단국대학교"))).isZero();
        }

        @Test
        @DisplayName("campusScope 가 null 이거나 비어 있으면 가산점이 없다")
        void blankScopeScoresNothing() {
            User user = user("단국대학교", "죽전");

            assertThat(score(user, event(null))).isZero();
            assertThat(score(user, event("   "))).isZero();
        }

        @Test
        @DisplayName("유저의 학교/캠퍼스가 비어 있어도 ALL 이면 가산점을 준다")
        void userWithoutSchoolStillScoresOnAll() {
            User user = user(null, null);

            assertThat(score(user, event(Event.CAMPUS_SCOPE_ALL))).isEqualTo(SCORE_CAMPUS);
            assertThat(score(user, event("단국대학교"))).isZero();
        }
    }

    @Nested
    @DisplayName("대상 대학교 매칭 가산점")
    class TargetSchoolMatching {

        @Test
        @DisplayName("대상 대학교가 유저의 학교와 맞으면 가산점을 준다")
        void schoolMatches() {
            assertThat(score(user("단국대학교", null), schoolEvent("단국대학교"))).isEqualTo(SCORE_SCHOOL);
        }

        @Test
        @DisplayName("여러 학교가 콤마로 들어와도 그중 하나면 가산점을 준다")
        void matchesOneOfSeveralSchools() {
            assertThat(score(user("고려대학교", null), schoolEvent("단국대학교,고려대학교")))
              .isEqualTo(SCORE_SCHOOL);
        }

        @Test
        @DisplayName("표기가 짧아도 부분일치로 잡는다")
        void matchesPartially() {
            assertThat(score(user("단국대", null), schoolEvent("단국대학교"))).isEqualTo(SCORE_SCHOOL);
        }

        @Test
        @DisplayName("다른 학교면 가산점이 없다")
        void differentSchoolScoresNothing() {
            assertThat(score(user("서울대학교", null), schoolEvent("단국대학교"))).isZero();
        }

        @Test
        @DisplayName("대상 대학교가 비어 있으면(전국 대상) 이 축의 가산점은 없다")
        void blankTargetSchoolScoresNothing() {
            assertThat(score(user("단국대학교", null), schoolEvent(null))).isZero();
        }

        @Test
        @DisplayName("유저의 학교가 비어 있으면 가산점이 없다")
        void userWithoutSchoolScoresNothing() {
            assertThat(score(user(null, null), schoolEvent("단국대학교"))).isZero();
        }
    }

    @Nested
    @DisplayName("희망직무 매칭 가산점")
    class InterestJobMatching {

        private static final int SCORE_PRIMARY = 30;
        private static final int SCORE_SECONDARY = 20;
        private static final int SCORE_TERTIARY = 10;

        @Test
        @DisplayName("제목에 희망직무가 그대로 있으면 1순위 만점이다")
        void exactMatchInTitleScoresFull() {
            User user = User.builder().interestJobPrimary("백엔드 개발자").build();
            Event event = textEvent("백엔드 개발자 모집", null, null);

            assertThat(score(user, event)).isEqualTo(SCORE_PRIMARY);
        }

        @Test
        @DisplayName("설명·요약 어디에 있어도 같은 만점이다 — 제목만 보지 않는다")
        void matchesDescriptionAndSummary() {
            User user = User.builder().interestJobPrimary("기획").build();

            assertThat(score(user, textEvent(null, "기획 파트 구합니다", null)))
              .isEqualTo(SCORE_PRIMARY);
            assertThat(score(user, textEvent(null, null, "기획 역량 우대")))
              .isEqualTo(SCORE_PRIMARY);
        }

        @Test
        @DisplayName("키워드 일부만 맞으면 비례 점수다 (백엔드 개발자 → 백엔드만 맞으면 절반)")
        void partialKeywordScoresProportionally() {
            User user = User.builder().interestJobPrimary("백엔드 개발자").build();
            Event event = textEvent("백엔드 스터디", null, null);

            assertThat(score(user, event)).isEqualTo(SCORE_PRIMARY / 2);
        }

        @Test
        @DisplayName("한 글자 토큰은 세지 않는다 — 오탐(AI ↔ email)을 줄이기 위해서다")
        void ignoresSingleCharacterTokens() {
            User user = User.builder().interestJobPrimary("A 백엔드").build();
            Event event = textEvent("백엔드 모집", null, null);

            // "A" 는 버리고 "백엔드" 만 맞아서 1/2.
            assertThat(score(user, event)).isEqualTo(SCORE_PRIMARY / 2);
        }

        @Test
        @DisplayName("1·2·3순위가 각각 본문에 있으면 점수를 더한다")
        void sumsPrimarySecondaryTertiary() {
            User user = User.builder()
              .interestJobPrimary("백엔드")
              .interestJobSecondary("기획")
              .interestJobTertiary("디자인")
              .build();
            Event event = textEvent("백엔드 기획 디자인 해커톤", null, null);

            assertThat(score(user, event))
              .isEqualTo(SCORE_PRIMARY + SCORE_SECONDARY + SCORE_TERTIARY);
        }

        @Test
        @DisplayName("본문에 희망직무가 없으면 이 축은 0점이다")
        void noKeywordScoresNothing() {
            User user = User.builder().interestJobPrimary("서버 운영자").build();
            Event event = textEvent("프론트엔드 해커톤", "React", "UI");

            assertThat(score(user, event)).isZero();
        }

        @Test
        @DisplayName("공백만 있는 희망직무는 점수를 주지 않는다")
        void blankInterestJobScoresNothing() {
            User user = User.builder().interestJobPrimary("   ").build();
            Event event = textEvent("백엔드 모집", null, null);

            assertThat(score(user, event)).isZero();
        }
    }

    @Nested
    @DisplayName("전공/단과대 매칭 가산점 (폐기 예정이지만 기존 데이터에 남아 있다)")
    class MajorCollegeMatching {

        private static final int SCORE_MAJOR = 15;
        private static final int SCORE_COLLEGE = 10;

        @Test
        @DisplayName("전공이 target_colleges 에 있으면 가산점을 준다")
        void majorMatches() {
            User user = User.builder().major("소프트웨어학과").build();
            Event event = collegesEvent("소프트웨어학과,컴퓨터공학과");

            assertThat(score(user, event)).isEqualTo(SCORE_MAJOR);
        }

        @Test
        @DisplayName("단과대가 target_colleges 에 있으면 가산점을 준다")
        void collegeMatches() {
            User user = User.builder().college("SW융합대학").build();
            Event event = collegesEvent("SW융합대학");

            assertThat(score(user, event)).isEqualTo(SCORE_COLLEGE);
        }

        @Test
        @DisplayName("전공과 단과대가 둘 다 맞으면 점수를 더한다")
        void majorAndCollegeStack() {
            User user = User.builder().major("소프트웨어학과").college("SW융합대학").build();
            Event event = collegesEvent("SW융합대학 소프트웨어학과");

            assertThat(score(user, event)).isEqualTo(SCORE_MAJOR + SCORE_COLLEGE);
        }

        @Test
        @DisplayName("target_colleges 가 비어 있으면 이 축은 0점이다")
        void blankTargetCollegesScoresNothing() {
            User user = User.builder().major("소프트웨어학과").college("SW융합대학").build();

            assertThat(score(user, collegesEvent(null))).isZero();
        }

        @Test
        @DisplayName("전공이 target_colleges 에 없으면 이 축은 0점이다")
        void majorMismatchScoresNothing() {
            User user = User.builder().major("경영학과").build();

            assertThat(score(user, collegesEvent("소프트웨어학과,컴퓨터공학과"))).isZero();
        }

        @Test
        @DisplayName("단과대가 target_colleges 에 없으면 이 축은 0점이다")
        void collegeMismatchScoresNothing() {
            User user = User.builder().college("경영대학").build();

            assertThat(score(user, collegesEvent("SW융합대학"))).isZero();
        }
    }

    @Test
    @DisplayName("희망직무가 null 이면 0점이다")
    void nullInterestJobScoresNothing() throws Exception {
        var method = EventMatchingService.class.getDeclaredMethod(
          "matchInterestJob", String.class, Event.class, int.class);
        method.setAccessible(true);

        assertThat(method.invoke(service, null, new Event(), 30)).isEqualTo(0);
    }

    @Test
    @DisplayName("키워드 추출은 빈 문자열을 버린다")
    void extractKeywordsDropsBlank() throws Exception {
        var method = EventMatchingService.class.getDeclaredMethod("extractKeywords", String.class);
        method.setAccessible(true);

        assertThat((String[]) method.invoke(service, new Object[]{null})).isEmpty();
        assertThat((String[]) method.invoke(service, "   ")).isEmpty();
    }

    @Test
    @DisplayName("유저의 학교/캠퍼스가 공백이면 campusScope 대조는 하지 않는다")
    void blankSchoolAndCampusDoNotMatchScope() {
        User user = user("  ", "  ");

        assertThat(score(user, event("단국대학교"))).isZero();
    }

    private int score(User user, Event event) {
        return service.calculateRelevanceScore(user, event);
    }

    // 캠퍼스 외 항목(희망직무/전공/단과대)은 모두 null 로 두어 점수가 캠퍼스 가산점만 반영되게 한다.
    private User user(String school, String campus) {
        return User.builder()
          .school(school)
          .campus(campus)
          .build();
    }

    @SuppressWarnings("deprecation") // campusScope 는 targetSchool 로 대체 중이지만 동작은 유지한다.
    private Event event(String campusScope) {
        Event event = new Event();
        event.setCampusScope(campusScope);
        return event;
    }

    // campusScope 는 비워 둔다 — 캠퍼스 가산점이 섞이면 대상 대학교 점수만 떼어 보기 어렵다.
    private Event schoolEvent(String targetSchool) {
        Event event = new Event();
        event.setTargetSchool(targetSchool);
        return event;
    }

    private Event textEvent(String title, String description, String summarized) {
        Event event = new Event();
        event.setTitle(title);
        event.setDescription(description);
        event.setSummarizedDescription(summarized);
        return event;
    }

    @SuppressWarnings("deprecation")
    private Event collegesEvent(String targetColleges) {
        Event event = new Event();
        event.setTarget_colleges(targetColleges);
        return event;
    }
}

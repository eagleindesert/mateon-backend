package com.example.mateon.events.service;

import com.example.mateon.events.models.Event;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventMatchingServiceTest {

    private static final int SCORE_CAMPUS = 5;

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

    private Event event(String campusScope) {
        Event event = new Event();
        event.setCampusScope(campusScope);
        return event;
    }
}

package com.example.mateon.matching.service;

import com.example.mateon.aichat.domain.IntentPrefixKind;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentExtractPrefixFactoryTest {

    @Test
    @DisplayName("토글이 꺼져 있으면 빈 목록이다")
    void offYieldsEmpty() {
        User user = User.builder()
          .name("김루미")
          .school("단국대학교")
          .major("소프트웨어학과")
          .portfolio("게시판 CRUD")
          .build();

        assertThat(IntentExtractPrefixFactory.from(user)).isEmpty();
    }

    @Test
    @DisplayName("프로필 토글이 켜져 있으면 [자기소개서] 라벨과 칸이 붙는다")
    void profileToggleBuildsLabeledBody() {
        User user = User.builder()
          .name("김루미")
          .school("단국대학교")
          .campus("죽전")
          .major("소프트웨어학과")
          .grade("3학년")
          .interestJobPrimary("백엔드")
          .tagline("협업 좋아합니다")
          .email("secret@example.com")
          .matchIncludeProfile(true)
          .build();

        assertThat(IntentExtractPrefixFactory.from(user)).singleElement().satisfies(prefix -> {
            assertThat(prefix.kind()).isEqualTo(IntentPrefixKind.PROFILE);
            assertThat(prefix.content()).startsWith("[자기소개서]\n");
            assertThat(prefix.content()).contains("학교: 단국대학교");
            assertThat(prefix.content()).contains("캠퍼스: 죽전");
            assertThat(prefix.content()).contains("전공: 소프트웨어학과");
            assertThat(prefix.content()).contains("학년: 3학년");
            assertThat(prefix.content()).contains("관심직무: 백엔드");
            assertThat(prefix.content()).contains("한 줄 소개: 협업 좋아합니다");
            assertThat(prefix.content()).doesNotContain("김루미");
            assertThat(prefix.content()).doesNotContain("secret@example.com");
        });
    }

    @Test
    @DisplayName("빈 칸은 건너뛰고, 프로필 칸이 하나도 없으면 접두 자체가 없다")
    void skipsBlankProfileFields() {
        User user = User.builder()
          .name("김루미")
          .matchIncludeProfile(true)
          .build();

        assertThat(IntentExtractPrefixFactory.from(user)).isEmpty();
    }

    @Test
    @DisplayName("포트폴리오 토글이 켜져 있고 본문이 있으면 [포트폴리오] 라벨이 붙는다")
    void portfolioToggleBuildsLabeledBody() {
        User user = User.builder()
          .name("김루미")
          .portfolio("게시판 CRUD")
          .matchIncludePortfolio(true)
          .build();

        assertThat(IntentExtractPrefixFactory.from(user)).singleElement().satisfies(prefix -> {
            assertThat(prefix.kind()).isEqualTo(IntentPrefixKind.PORTFOLIO);
            assertThat(prefix.content()).isEqualTo("[포트폴리오]\n게시판 CRUD");
        });
    }

    @Test
    @DisplayName("포트폴리오 토글이 켜져 있어도 본문이 비면 접두가 없다")
    void emptyPortfolioOmitsPrefix() {
        User user = User.builder()
          .name("김루미")
          .portfolio("   ")
          .matchIncludePortfolio(true)
          .build();

        assertThat(IntentExtractPrefixFactory.from(user)).isEmpty();
    }
}

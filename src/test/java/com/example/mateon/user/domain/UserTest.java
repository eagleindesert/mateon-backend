package com.example.mateon.user.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유저 엔티티에서 컨트롤러 테스트가 안 건드리는 전이만 고정한다.
 *
 * <p>
 * {@code update()} 의 필드별 null 가드는 {@code UserProfileControllerTest} 가 PUT 으로 이미
 * 본다. 남는 것은 비밀번호·학교 인증·소셜 연동처럼 다른 서비스가 부르는 전이와, JPA
 * 콜백이 시각을 채우는 {@link UserEmbedding} 이다.
 */
class UserTest {

    @Test
    @DisplayName("보낸 필드만 바뀐다 — 생략한 항목은 그대로다")
    void updateTouchesOnlyGivenFields() {
        User user = User.builder()
          .name("김루미")
          .school("단국대학교")
          .campus("죽전")
          .college("SW융합대학")
          .major("소프트웨어학과")
          .grade("3학년")
          .interestJobPrimary("백엔드")
          .interestJobSecondary("기획")
          .interestJobTertiary("디자인")
          .tagline("한 줄")
          .portfolio("기존 포트폴리오")
          .build();

        user.update("새이름", "고려대학교", "서울", "정보대학", "컴퓨터학과", "4학년",
          "프론트", "PM", "일러스트", "새 한 줄", "새 포트폴리오");

        assertThat(user.getName()).isEqualTo("새이름");
        assertThat(user.getSchool()).isEqualTo("고려대학교");
        assertThat(user.getCampus()).isEqualTo("서울");
        assertThat(user.getCollege()).isEqualTo("정보대학");
        assertThat(user.getMajor()).isEqualTo("컴퓨터학과");
        assertThat(user.getGrade()).isEqualTo("4학년");
        assertThat(user.getInterestJobPrimary()).isEqualTo("프론트");
        assertThat(user.getInterestJobSecondary()).isEqualTo("PM");
        assertThat(user.getInterestJobTertiary()).isEqualTo("일러스트");
        assertThat(user.getTagline()).isEqualTo("새 한 줄");
        assertThat(user.getPortfolio()).isEqualTo("새 포트폴리오");
    }

    @Test
    @DisplayName("전부 null 이면 아무 필드도 지우지 않는다")
    void updateWithAllNullLeavesValues() {
        User user = User.builder()
          .name("김루미")
          .school("단국대학교")
          .campus("죽전")
          .college("SW융합대학")
          .major("소프트웨어학과")
          .grade("3학년")
          .interestJobPrimary("백엔드")
          .interestJobSecondary("기획")
          .interestJobTertiary("디자인")
          .tagline("한 줄")
          .portfolio("기존")
          .build();

        user.update(null, null, null, null, null, null, null, null, null, null, null);

        assertThat(user.getName()).isEqualTo("김루미");
        assertThat(user.getSchool()).isEqualTo("단국대학교");
        assertThat(user.getCampus()).isEqualTo("죽전");
        assertThat(user.getCollege()).isEqualTo("SW융합대학");
        assertThat(user.getMajor()).isEqualTo("소프트웨어학과");
        assertThat(user.getGrade()).isEqualTo("3학년");
        assertThat(user.getInterestJobPrimary()).isEqualTo("백엔드");
        assertThat(user.getInterestJobSecondary()).isEqualTo("기획");
        assertThat(user.getInterestJobTertiary()).isEqualTo("디자인");
        assertThat(user.getTagline()).isEqualTo("한 줄");
        assertThat(user.getPortfolio()).isEqualTo("기존");
    }

    @Test
    @DisplayName("학교 인증은 이메일을 저장하고 재학생 상태로 바꾼다")
    void verifySchoolMarksVerified() {
        User user = User.builder().schoolVerified(false).build();

        user.verifySchool("rumi@dankook.ac.kr");

        assertThat(user.getSchoolEmail()).isEqualTo("rumi@dankook.ac.kr");
        assertThat(user.isSchoolVerified()).isTrue();
    }

    @Test
    @DisplayName("소셜 연동은 provider 와 providerId 를 함께 바꾼다")
    void linkSocialSetsProviderPair() {
        User user = User.builder().provider(AuthProvider.LOCAL).build();

        user.linkSocial(AuthProvider.KAKAO, "kakao-123");

        assertThat(user.getProvider()).isEqualTo(AuthProvider.KAKAO);
        assertThat(user.getProviderId()).isEqualTo("kakao-123");
    }

    @Test
    @DisplayName("비밀번호 교체는 인코딩된 값을 그대로 넣는다")
    void updatePasswordStoresEncodedValue() {
        User user = User.builder().password("old").build();

        user.updatePassword("encoded-new");

        assertThat(user.getPassword()).isEqualTo("encoded-new");
    }

    @Test
    @DisplayName("임베딩 행은 persist/update 콜백에서 시각을 채운다")
    void embeddingLifecycleStampsTimestamps() {
        UserEmbedding embedding = new UserEmbedding();
        embedding.setUserId(1L);
        embedding.setEmbedding(new float[]{0.1f, 0.2f});

        embedding.onCreate();
        LocalDateTime created = embedding.getCreatedAt();
        assertThat(created).isNotNull();
        assertThat(embedding.getUpdatedAt()).isEqualTo(created);

        embedding.onUpdate();
        assertThat(embedding.getUpdatedAt()).isAfterOrEqualTo(created);
    }
}

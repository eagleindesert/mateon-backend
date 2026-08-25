package com.example.mateon.user.dto;

import com.example.mateon.teams.service.CollaborationTemperatureCalculator;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserCollaborationScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserResponse} 의 "싣지 않음 / 없음 / 있음" 3상태를 고정한다. 이 DTO 는 {@code /me} 와
 * 지원서 응답의 {@code applicant} 가 함께 쓰는데, 한쪽은 온도·활동을 싣고 한쪽은 싣지 않는다.
 * 그 둘을 프론트가 구분할 수 없게 되는 순간(= 건수가 0, 활동이 빈 배열로 나가는 순간) "평가 0 건",
 * "참여 활동 없음"과 "이 API 는 그 값을 안 준다"가 같은 응답이 되므로, 여기서 못박아 둔다.
 */
class UserResponseTest {

    @Test
    @DisplayName("온도·활동을 싣지 않는 경로는 건수까지 null 이다 - 0 이나 빈 배열이면 '없음'과 구분되지 않는다")
    void withoutScoreAndActivitiesFieldsAreNull() {
        UserResponse response = UserResponse.ofBasic(user());

        assertThat(response.getCollaborationTemperature()).isNull();
        assertThat(response.getCollaborationReviewCount()).isNull();
        assertThat(response.getParticipatedActivities()).isNull();
    }

    @Test
    @DisplayName("평가를 한 번도 안 받았으면 건수는 0 이고 온도는 기준점이다 - 집계 행이 없어도 값이 나간다")
    void neverReviewedUserGetsBaseTemperature() {
        UserResponse response = UserResponse.ofFull(user(), null, List.of());

        assertThat(response.getCollaborationTemperature())
          .isEqualByComparingTo(CollaborationTemperatureCalculator.INITIAL);
        assertThat(response.getCollaborationReviewCount()).isZero();
    }

    @Test
    @DisplayName("평가가 1건이어도 온도가 실린다 - 표본이 적다는 이유로 감추지 않는다")
    void temperatureIsCarriedWithASingleReview() {
        UserCollaborationScore score = UserCollaborationScore.init(1L);
        score.addRating(5);

        UserResponse response = UserResponse.ofFull(user(), score, List.of());

        assertThat(response.getCollaborationTemperature()).isEqualByComparingTo("37.0");
        assertThat(response.getCollaborationReviewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("평가가 충분하면 온도와 건수가 함께 실린다")
    void exposesTemperatureWhenEnoughReviews() {
        UserCollaborationScore score = UserCollaborationScore.init(1L);
        score.addRating(5);
        score.addRating(5);

        UserResponse response = UserResponse.ofFull(user(), score, List.of());

        assertThat(response.getCollaborationTemperature()).isEqualByComparingTo(score.getTemperature());
        assertThat(response.getCollaborationReviewCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("참여 활동이 없으면 null 이 아니라 빈 배열이다 - 프론트가 그대로 map 한다")
    void activityListIsEmptyNotNullWhenCarried() {
        UserResponse response = UserResponse.ofFull(user(), null, List.of());

        assertThat(response.getParticipatedActivities()).isEmpty();
    }

    @Test
    @DisplayName("참여 활동은 마이페이지·공개 프로필과 같은 타입으로 실린다")
    void carriesActivitiesWithSharedType() {
        MyPageResponseDTO.ActivitySummaryDTO activity = MyPageResponseDTO.ActivitySummaryDTO.builder()
          .id(11L)
          .title("교내 해커톤 팀")
          .category("교내")
          .build();

        UserResponse response = UserResponse.ofFull(user(), null, List.of(activity));

        assertThat(response.getParticipatedActivities()).singleElement()
          .satisfies(summary -> {
              assertThat(summary.getId()).isEqualTo(11L);
              assertThat(summary.getTitle()).isEqualTo("교내 해커톤 팀");
              assertThat(summary.getCategory()).isEqualTo("교내");
          });
    }

    @Test
    @DisplayName("/mypage 가 주는 필드 이름이 /me 에 모두 있다 - 폐기 전 확인용")
    void coversEveryMyPageFieldName() {
        // /mypage 는 프론트가 쓰지 않는 중복 API 라 폐기 예정이다. 지우기 전에 "그쪽에만 있는
        // 필드가 없다"를 기계적으로 확인할 수 있어야 해서 남겨 둔다.
        //
        // 값이 아니라 키 구성을 비교한다. 응답 JSON 으로 검사하면 픽스처에서 null 인 필드
        // (profileImageUrl 등) 때문에 "필드가 없다"와 "값이 비었다"가 섞인다.
        assertThat(fieldNames(UserResponse.class))
          .containsAll(fieldNames(MyPageResponseDTO.class));
    }

    private List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
          .filter(field -> !field.isSynthetic())
          .map(Field::getName)
          .toList();
    }

    private User user() {
        return User.builder()
          .id(1L)
          .email("rumi@dankook.ac.kr")
          .name("김루미")
          .build();
    }
}

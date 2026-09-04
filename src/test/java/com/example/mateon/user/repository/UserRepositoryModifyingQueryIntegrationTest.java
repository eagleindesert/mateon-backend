package com.example.mateon.user.repository;

import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로필 이미지 URL 을 바꾸는 두 벌크 UPDATE 를 실제 Postgres 에 대고 고정한다.
 *
 * <p>
 * 이 둘은 비동기 워커가 부르는 유일한 DB 쓰기다. 워커 테스트는 리포지토리를 목으로 두어
 * "불렀는가"만 보므로, 쿼리가 정말 그 행만 고치고 나머지 컬럼은 건드리지 않는지, 그리고
 * compare-and-set 의 반환값이 0 과 1 로 갈리는지는 여기서만 확인된다. CAS 반환값은 워커가
 * "그 사이 새 사진이 올라왔다"를 판단하는 유일한 근거다.
 */
class UserRepositoryModifyingQueryIntegrationTest extends IntegrationTestBase {

    private static final String OLD_URL = "https://bucket.test/profile-images/old.png";
    private static final String NEW_URL = "https://bucket.test/profile-images/new.png";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 4, 12, 0, 0);

    @Autowired
    UserRepository userRepository;

    @Nested
    @DisplayName("updateProfileImageUrl")
    class UpdateProfileImageUrl {

        @Test
        @DisplayName("URL 과 updatedAt 만 바뀌고 다른 컬럼은 그대로다")
        void updatesOnlyUrlAndTimestamp() {
            Long id = newUser("김루미", OLD_URL);

            int updated = userRepository.updateProfileImageUrl(id, NEW_URL, NOW);

            User reloaded = userRepository.findById(id).orElseThrow();
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getProfileImageUrl()).isEqualTo(NEW_URL);
            assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW);
            assertThat(reloaded.getName()).isEqualTo("김루미");
        }

        @Test
        @DisplayName("null 을 주면 이미지 없음 상태가 된다")
        void nullClearsUrl() {
            Long id = newUser("김루미", OLD_URL);

            userRepository.updateProfileImageUrl(id, null, NOW);

            assertThat(userRepository.findById(id).orElseThrow().getProfileImageUrl()).isNull();
        }

        @Test
        @DisplayName("없는 사용자는 0 행이다")
        void unknownUserUpdatesNothing() {
            assertThat(userRepository.updateProfileImageUrl(-1L, NEW_URL, NOW)).isZero();
        }
    }

    @Nested
    @DisplayName("clearProfileImageUrlIfMatches — compare-and-set")
    class ClearIfMatches {

        @Test
        @DisplayName("저장된 URL 이 기대값과 같으면 비우고 1 을 돌려준다")
        void clearsWhenMatches() {
            Long id = newUser("김루미", OLD_URL);

            int updated = userRepository.clearProfileImageUrlIfMatches(id, OLD_URL, NOW);

            User reloaded = userRepository.findById(id).orElseThrow();
            assertThat(updated).isEqualTo(1);
            assertThat(reloaded.getProfileImageUrl()).isNull();
            assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("그 사이 URL 이 바뀌었으면 건드리지 않고 0 을 돌려준다 (새 사진을 지우면 안 된다)")
        void keepsNewUrlWhenChangedMidFlight() {
            Long id = newUser("김루미", NEW_URL);

            int updated = userRepository.clearProfileImageUrlIfMatches(id, OLD_URL, NOW);

            assertThat(updated).isZero();
            assertThat(userRepository.findById(id).orElseThrow().getProfileImageUrl())
              .isEqualTo(NEW_URL);
        }

        @Test
        @DisplayName("이미 비어 있으면 0 이다 — NULL 은 어떤 기대값과도 같지 않다")
        void nullStoredNeverMatches() {
            Long id = newUser("김루미", null);

            assertThat(userRepository.clearProfileImageUrlIfMatches(id, OLD_URL, NOW)).isZero();
        }

        @Test
        @DisplayName("다른 사용자의 같은 URL 은 건드리지 않는다")
        void scopedToUser() {
            Long mine = newUser("나", OLD_URL);
            Long other = newUser("남", OLD_URL);

            userRepository.clearProfileImageUrlIfMatches(mine, OLD_URL, NOW);

            assertThat(userRepository.findById(other).orElseThrow().getProfileImageUrl())
              .isEqualTo(OLD_URL);
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private Long newUser(String name, String profileImageUrl) {
        return userRepository.saveAndFlush(User.builder()
          .email(UUID.randomUUID() + "@test.ac.kr")
          .name(name)
          .profileImageUrl(profileImageUrl)
          .build()).getId();
    }
}

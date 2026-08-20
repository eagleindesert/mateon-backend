package com.example.mateon.user.repository;

import com.example.mateon.user.domain.AuthProvider;
import com.example.mateon.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsBySchoolEmail(String schoolEmail);

    // 소셜 유저 신원 조회: (provider, providerId) 조합으로 재방문 유저를 찾는다.
    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    /**
     * 프로필 이미지 URL 만 바꾼다 (null 이면 이미지 없음 상태로).
     *
     * <p>
     * 엔티티를 로드해 save 하지 않는 이유: 이 갱신은 비동기 워커(ProfileImageWorker)가 버킷
     * 작업을 끝낸 뒤에 실행되고, 그 사이 사용자가 이름·학과를 고쳤을 수 있다. 워커가 들고 있던
     * 스냅샷을 merge 하면 그 수정이 옛 값으로 되돌아간다.
     *
     * <p>
     * @Transactional 을 메서드에 붙인 이유: 워커는 트랜잭션 없이 돌아야 한다(버킷 호출이
     * 트랜잭션에 묶이면 "삭제 성공 → DB 반영" 순서 보장이 커밋 시점에 휘둘린다). 그래서 갱신
     * 하나하나가 자기 트랜잭션을 연다.
     *
     * @param now updatedAt 에 넣을 값. 벌크 UPDATE 는 @LastModifiedDate 감사(auditing)를 타지
     * 않으므로 직접 넘긴다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.profileImageUrl = :url, u.updatedAt = :now WHERE u.id = :userId")
    int updateProfileImageUrl(@Param("userId") Long userId,
      @Param("url") String url,
      @Param("now") LocalDateTime now);

    /**
     * 저장된 URL 이 {@code expected} 와 같을 때만 비운다 (compare-and-set).
     *
     * <p>
     * 비동기로 버킷 객체를 지우는 동안 사용자가 새 사진을 올렸을 수 있다. 그때 무조건 null 로
     * 덮으면 방금 올린 사진이 화면에서 사라진다 — 지운 객체의 URL 이 아직 그대로일 때만 비운다.
     *
     * @return 갱신된 행 수. 0 이면 그 사이 URL 이 바뀐 것이다(덮어쓰지 않고 넘어갔다는 뜻).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.profileImageUrl = NULL, u.updatedAt = :now"
      + " WHERE u.id = :userId AND u.profileImageUrl = :expected")
    int clearProfileImageUrlIfMatches(@Param("userId") Long userId,
      @Param("expected") String expected,
      @Param("now") LocalDateTime now);
}

package com.example.mateon.user.repository;

import com.example.mateon.user.domain.AuthProvider;
import com.example.mateon.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsBySchoolEmail(String schoolEmail);
    // 소셜 유저 신원 조회: (provider, providerId) 조합으로 재방문 유저를 찾는다.
    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);
}


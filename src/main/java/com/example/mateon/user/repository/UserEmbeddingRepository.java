package com.example.mateon.user.repository;

import com.example.mateon.user.domain.UserEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserEmbeddingRepository extends JpaRepository<UserEmbedding, Long> {
}

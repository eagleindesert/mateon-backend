package com.example.mateon.events.repository;

import com.example.mateon.events.domain.EventEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventEmbeddingRepository extends JpaRepository<EventEmbedding, Long> {

    /**
     * 유사도 지도 후보. 행이 있어도 embedding 이 null 이면 아직/실패라 보낼 벡터가 없다.
     */
    List<EventEmbedding> findByEmbeddingIsNotNullAndEventIdNot(Long eventId);
}

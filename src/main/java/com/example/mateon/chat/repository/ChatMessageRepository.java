package com.example.mateon.chat.repository;

import com.example.mateon.chat.domain.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // 방의 최신 메시지 1건 (방 목록 미리보기용)
    Optional<ChatMessage> findFirstByRoomIdOrderByIdDesc(Long roomId);

    // 안읽음 수: lastReadMessageId 보다 큰 id 의 메시지 개수
    long countByRoomIdAndIdGreaterThan(Long roomId, Long lastReadMessageId);

    // lastReadMessageId 가 null 인 경우(아무것도 안 읽음) 방 전체 메시지 개수
    long countByRoomId(Long roomId);

    // 이력 조회 (초기 로드): 최신순 size 건
    List<ChatMessage> findByRoomIdOrderByIdDesc(Long roomId, Pageable pageable);

    // 이력 조회 (과거 더보기): beforeId 이전(작은 id) 최신순 size 건
    List<ChatMessage> findByRoomIdAndIdLessThanOrderByIdDesc(Long roomId, Long beforeId, Pageable pageable);
}

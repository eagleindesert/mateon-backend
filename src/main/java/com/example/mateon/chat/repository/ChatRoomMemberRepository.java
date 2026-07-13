package com.example.mateon.chat.repository;

import com.example.mateon.chat.domain.ChatRoomMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

    // 내가 참여한 방 목록 (최신 대화순 정렬은 서비스에서 room.updatedAt 으로 처리)
    List<ChatRoomMember> findAllByUserId(Long userId);

    // 특정 방의 특정 사용자 멤버십 (멤버십 검증 / 읽음 처리용)
    Optional<ChatRoomMember> findByRoomIdAndUserId(Long roomId, Long userId);

    // 특정 방의 모든 멤버 (알림 발송 대상 조회용)
    List<ChatRoomMember> findAllByRoomId(Long roomId);

    boolean existsByRoomIdAndUserId(Long roomId, Long userId);
}

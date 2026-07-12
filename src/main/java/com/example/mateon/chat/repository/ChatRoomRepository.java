package com.example.mateon.chat.repository;

import com.example.mateon.chat.domain.ChatRoom;
import com.example.mateon.chat.domain.RoomType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    // 두 사용자가 모두 멤버인 DM 방을 조회 (DM 방은 정확히 2명이 멤버라는 전제).
    // 멤버십 테이블을 통해 user1, user2 를 모두 포함하는 DM 타입 방을 찾는다.
    @Query("""
            select m1.room from ChatRoomMember m1
            join ChatRoomMember m2 on m1.room = m2.room
            where m1.room.type = :type
              and m1.user.id = :userId1
              and m2.user.id = :userId2
            """)
    Optional<ChatRoom> findDmRoom(@Param("type") RoomType type,
                                  @Param("userId1") Long userId1,
                                  @Param("userId2") Long userId2);
}

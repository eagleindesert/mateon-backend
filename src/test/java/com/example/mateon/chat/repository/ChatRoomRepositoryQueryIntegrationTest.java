package com.example.mateon.chat.repository;

import com.example.mateon.chat.domain.ChatRoom;
import com.example.mateon.chat.domain.ChatRoomMember;
import com.example.mateon.chat.domain.RoomType;
import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DM 방 조회 JPQL 을 실제 Postgres 에 대고 고정한다.
 *
 * <p>
 * {@code getOrCreateDmRoom} 의 멱등성이 전부 이 쿼리에 걸려 있다. 방을 못 찾으면 같은 두
 * 사람의 DM 방이 요청마다 하나씩 늘어나고, 반대로 그룹 방까지 잡으면 팀 채팅방이 DM 으로
 * 재사용된다. 서비스 테스트는 이 쿼리를 목으로 두므로 어느 쪽도 거기서는 드러나지 않는다.
 */
class ChatRoomRepositoryQueryIntegrationTest extends IntegrationTestBase {

    @Autowired
    ChatRoomRepository chatRoomRepository;
    @Autowired
    ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired
    UserRepository userRepository;

    private User me;
    private User you;
    private User stranger;

    @BeforeEach
    void setUp() {
        me = newUser("나");
        you = newUser("너");
        stranger = newUser("남");
    }

    @Test
    @DisplayName("두 사람이 모두 멤버인 DM 방을 찾는다")
    void findsDmRoomWithBothMembers() {
        ChatRoom room = newRoom(RoomType.DM, me, you);

        assertThat(chatRoomRepository.findDmRoom(RoomType.DM, me.getId(), you.getId()))
          .map(ChatRoom::getId).hasValue(room.getId());
    }

    @Test
    @DisplayName("인자 순서를 바꿔도 같은 방이다 (누가 먼저 열었는지와 무관하다)")
    void orderOfUsersDoesNotMatter() {
        ChatRoom room = newRoom(RoomType.DM, me, you);

        assertThat(chatRoomRepository.findDmRoom(RoomType.DM, you.getId(), me.getId()))
          .map(ChatRoom::getId).hasValue(room.getId());
    }

    @Test
    @DisplayName("한쪽만 멤버인 DM 방은 잡히지 않는다")
    void ignoresDmRoomWithOnlyOneOfThem() {
        newRoom(RoomType.DM, me, stranger);

        assertThat(chatRoomRepository.findDmRoom(RoomType.DM, me.getId(), you.getId())).isEmpty();
    }

    @Test
    @DisplayName("둘 다 들어 있어도 GROUP 방은 잡히지 않는다")
    void ignoresGroupRoom() {
        newRoom(RoomType.GROUP, me, you);

        assertThat(chatRoomRepository.findDmRoom(RoomType.DM, me.getId(), you.getId())).isEmpty();
    }

    @Test
    @DisplayName("여러 DM 방 중 그 상대와의 방만 고른다")
    void picksTheRightOneAmongSeveralDms() {
        newRoom(RoomType.DM, me, stranger);
        ChatRoom withYou = newRoom(RoomType.DM, me, you);
        newRoom(RoomType.DM, you, stranger);

        assertThat(chatRoomRepository.findDmRoom(RoomType.DM, me.getId(), you.getId()))
          .map(ChatRoom::getId).hasValue(withYou.getId());
    }

    // --- 픽스처 -------------------------------------------------------------

    private User newUser(String name) {
        return userRepository.save(User.builder()
          .email(UUID.randomUUID() + "@test.ac.kr")
          .name(name)
          .build());
    }

    private ChatRoom newRoom(RoomType type, User first, User second) {
        ChatRoom room = chatRoomRepository.save(ChatRoom.builder().type(type).build());
        chatRoomMemberRepository.save(ChatRoomMember.builder().room(room).user(first).build());
        chatRoomMemberRepository.save(ChatRoomMember.builder().room(room).user(second).build());
        chatRoomMemberRepository.flush();
        return room;
    }
}

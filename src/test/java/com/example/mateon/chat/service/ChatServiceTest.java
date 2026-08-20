package com.example.mateon.chat.service;

import com.example.mateon.chat.domain.ChatMessage;
import com.example.mateon.chat.domain.ChatRoom;
import com.example.mateon.chat.domain.ChatRoomMember;
import com.example.mateon.chat.domain.RoomType;
import com.example.mateon.chat.dto.response.ChatMessageResponse;
import com.example.mateon.chat.dto.response.ChatRoomResponse;
import com.example.mateon.chat.repository.ChatMessageRepository;
import com.example.mateon.chat.repository.ChatRoomMemberRepository;
import com.example.mateon.chat.repository.ChatRoomRepository;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.support.TestEntities;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 채팅의 저장·브로드캐스트·조회 규칙을 고정한다.
 *
 * <p>
 * 실시간 경로는 원래 {@code scripts/test/for-api/10_chat.ps1} 이 서버를 띄워 놓고 검증했는데,
 * 그건 서버와 DB 가 살아 있어야 돌고 CI 에서는 돌지 않는다. 여기서는 {@code SimpMessagingTemplate}
 * 을 목으로 두고 <b>무엇을 어디로 보내는지</b>만 잡아둔다.
 *
 * <p>
 * 가장 조용히 깨질 것은 <b>목적지 문자열</b>이다. {@code /topic/room.5} 는 점이지 슬래시가
 * 아니다. 프론트가 그 문자열 그대로 구독하고 있어서, 서버가 {@code /topic/room/5} 로 바꾸면
 * 서버 로그에는 아무 에러도 없이 메시지만 아무에게도 도착하지 않는다.
 *
 * <p>
 * 두 번째는 <b>persist-then-push 순서</b>다. 저장 전에 브로드캐스트하면, 트랜잭션이 뒤에
 * 롤백됐을 때 화면에만 존재하는 유령 메시지가 남는다.
 *
 * <p>
 * 세 번째는 <b>미리보기 30자 경계</b>. 알림 본문 길이는 눈으로 보면 맞아 보이지만
 * {@code >} 와 {@code >=} 를 바꿔도 티가 나지 않는다.
 *
 * <p>
 * 참고로 방 픽스처에는 항상 {@code touch()} 로 {@code updatedAt} 을 채운다. 운영에서는
 * {@code @LastModifiedDate} 가 not-null 컬럼을 항상 채우지만, JPA 감사가 없는 단위 테스트에서는
 * null 로 남아 목록 정렬 비교자에서 NPE 가 난다 — 테스트만의 문제이므로 픽스처에서 맞춰준다.
 */
class ChatServiceTest {

    private static final long ME = 1L;
    private static final long PARTNER = 2L;
    private static final long ROOM_ID = 5L;

    private ChatRoomRepository chatRoomRepository;
    private ChatRoomMemberRepository chatRoomMemberRepository;
    private ChatMessageRepository chatMessageRepository;
    private UserRepository userRepository;
    private SimpMessagingTemplate messagingTemplate;
    private NotificationService notificationService;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        chatRoomMemberRepository = mock(ChatRoomMemberRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        userRepository = mock(UserRepository.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        notificationService = mock(NotificationService.class);

        chatService = new ChatService(chatRoomRepository, chatRoomMemberRepository,
          chatMessageRepository, userRepository, messagingTemplate, notificationService);
    }

    @Nested
    @DisplayName("DM 방 조회-or-생성 — 멱등이 이 메서드의 존재 이유다")
    class GetOrCreateDmRoom {

        @Test
        @DisplayName("자기 자신과의 DM 은 리포지토리를 건드리기 전에 막는다")
        void cannotChatWithSelf() {
            assertThatThrownBy(() -> chatService.getOrCreateDmRoom(ME, ME))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.CANNOT_CHAT_WITH_SELF);

            verify(userRepository, never()).findById(anyLong());
            verify(chatRoomRepository, never()).findDmRoom(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("이미 방이 있으면 그대로 돌려주고 아무것도 만들지 않는다")
        void reusesExistingRoom() {
            ChatRoom existing = room(RoomType.DM, ROOM_ID);
            givenBothUsersExist();
            when(chatRoomRepository.findDmRoom(RoomType.DM, ME, PARTNER)).thenReturn(Optional.of(existing));

            assertThat(chatService.getOrCreateDmRoom(ME, PARTNER)).isSameAs(existing);

            verify(chatRoomRepository, never()).save(any());
            verify(chatRoomMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("없으면 방 하나와 멤버 두 행을 만든다")
        void createsRoomWithBothMembers() {
            givenBothUsersExist();
            when(chatRoomRepository.findDmRoom(RoomType.DM, ME, PARTNER)).thenReturn(Optional.empty());
            ChatRoom created = room(RoomType.DM, ROOM_ID);
            when(chatRoomRepository.save(any())).thenReturn(created);

            assertThat(chatService.getOrCreateDmRoom(ME, PARTNER)).isSameAs(created);

            ArgumentCaptor<ChatRoomMember> members = ArgumentCaptor.forClass(ChatRoomMember.class);
            verify(chatRoomMemberRepository, org.mockito.Mockito.times(2)).save(members.capture());
            assertThat(members.getAllValues())
              .extracting(m -> m.getUser().getId())
              .containsExactlyInAnyOrder(ME, PARTNER);
        }

        @Test
        @DisplayName("상대가 없는 사용자면 USER_NOT_FOUND")
        void unknownTarget() {
            when(userRepository.findById(ME)).thenReturn(Optional.of(user(ME, "나")));
            when(userRepository.findById(PARTNER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> chatService.getOrCreateDmRoom(ME, PARTNER))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("메시지 저장 + 브로드캐스트")
    class SaveAndBroadcast {

        @Test
        @DisplayName("목적지는 '/topic/room.5' 다 — 점이지 슬래시가 아니다 (프론트 구독 문자열)")
        void destinationUsesDotSeparator() {
            givenSenderIsMember();

            chatService.saveAndBroadcast(ME, ROOM_ID, "안녕하세요");

            verify(messagingTemplate).convertAndSend(eq("/topic/room." + ROOM_ID), any(Object.class));
        }

        @Test
        @DisplayName("저장 → 브로드캐스트 → 알림 순서다 (저장 전에 보내면 유령 메시지가 생긴다)")
        void persistsBeforePushing() {
            givenSenderIsMember();

            chatService.saveAndBroadcast(ME, ROOM_ID, "안녕하세요");

            InOrder order = inOrder(chatMessageRepository, messagingTemplate, notificationService);
            order.verify(chatMessageRepository).save(any());
            order.verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
            order.verify(notificationService).send(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("발신자에게는 알림을 보내지 않는다 (자기 메시지 알림은 잡음이다)")
        void doesNotNotifySender() {
            givenSenderIsMember();

            chatService.saveAndBroadcast(ME, ROOM_ID, "안녕하세요");

            ArgumentCaptor<User> receivers = ArgumentCaptor.forClass(User.class);
            verify(notificationService).send(receivers.capture(), anyString(), anyString(),
              eq(Notification.NotificationType.INFO));
            assertThat(receivers.getValue().getId()).isEqualTo(PARTNER);
        }

        @Test
        @DisplayName("발신자는 자기 메시지를 곧바로 읽은 것으로 처리한다 (안읽음 수가 1 로 시작하지 않도록)")
        void senderReadsOwnMessage() {
            ChatRoomMember senderMembership = givenSenderIsMember();

            chatService.saveAndBroadcast(ME, ROOM_ID, "안녕하세요");

            assertThat(senderMembership.getLastReadMessageId()).isEqualTo(77L);
        }

        @Test
        @DisplayName("방의 updatedAt 을 갱신한다 (목록 최신순 정렬 근거)")
        void touchesRoom() {
            ChatRoomMember membership = givenSenderIsMember();
            LocalDateTime before = membership.getRoom().getUpdatedAt();

            chatService.saveAndBroadcast(ME, ROOM_ID, "안녕하세요");

            assertThat(membership.getRoom().getUpdatedAt()).isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("정확히 30자인 미리보기는 그대로 나간다")
        void previewAtBoundaryIsUnchanged() {
            givenSenderIsMember();
            String exactly30 = "가".repeat(30);

            chatService.saveAndBroadcast(ME, ROOM_ID, exactly30);

            assertThat(capturedPreview()).isEqualTo(exactly30);
        }

        @Test
        @DisplayName("31자부터 앞 30자 + 말줄임표(…) 한 글자로 자른다")
        void previewIsTruncatedPast30() {
            givenSenderIsMember();

            chatService.saveAndBroadcast(ME, ROOM_ID, "가".repeat(31));

            assertThat(capturedPreview()).isEqualTo("가".repeat(30) + "…").hasSize(31);
        }

        @Test
        @DisplayName("알림 제목은 '보낸사람님의 메시지' 형식이다")
        void notificationTitle() {
            givenSenderIsMember();

            chatService.saveAndBroadcast(ME, ROOM_ID, "안녕");

            ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
            verify(notificationService).send(any(), title.capture(), anyString(), any());
            assertThat(title.getValue()).isEqualTo("나님의 메시지");
        }

        @Test
        @DisplayName("빈 내용은 저장도 브로드캐스트도 하지 않는다")
        void rejectsBlankContent() {
            for (String blank : new String[]{null, "", "   "}) {
                assertThatThrownBy(() -> chatService.saveAndBroadcast(ME, ROOM_ID, blank))
                  .isInstanceOf(MateonException.class)
                  .hasMessage("메시지 내용이 비어 있습니다.");
            }
            verify(chatMessageRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("방 멤버가 아니면 NOT_ROOM_MEMBER — 남의 방에 글을 넣을 수 없다")
        void rejectsNonMember() {
            when(chatRoomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ME)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> chatService.saveAndBroadcast(ME, ROOM_ID, "안녕"))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.NOT_ROOM_MEMBER);

            verify(chatMessageRepository, never()).save(any());
        }

        private String capturedPreview() {
            ArgumentCaptor<String> preview = ArgumentCaptor.forClass(String.class);
            verify(notificationService).send(any(), anyString(), preview.capture(), any());
            return preview.getValue();
        }
    }

    @Nested
    @DisplayName("방 목록")
    class GetMyRooms {

        @Test
        @DisplayName("아무것도 안 읽었으면 방 전체 메시지 수가 안읽음 수다")
        void unreadCountWhenNothingRead() {
            ChatRoom dm = room(RoomType.DM, ROOM_ID);
            ChatRoomMember mine = member(dm, user(ME, "나"), null);
            when(chatRoomMemberRepository.findAllByUserId(ME)).thenReturn(List.of(mine));
            when(chatRoomMemberRepository.findAllByRoomId(ROOM_ID))
              .thenReturn(List.of(mine, member(dm, user(PARTNER, "상대"), null)));
            when(chatMessageRepository.findFirstByRoomIdOrderByIdDesc(ROOM_ID)).thenReturn(Optional.empty());
            when(chatMessageRepository.countByRoomId(ROOM_ID)).thenReturn(12L);

            assertThat(chatService.getMyRooms(ME)).singleElement()
              .extracting(ChatRoomResponse::getUnreadCount).isEqualTo(12L);

            verify(chatMessageRepository, never()).countByRoomIdAndIdGreaterThan(anyLong(), anyLong());
        }

        @Test
        @DisplayName("읽은 위치가 있으면 그 이후 메시지만 센다")
        void unreadCountAfterLastRead() {
            ChatRoom dm = room(RoomType.DM, ROOM_ID);
            ChatRoomMember mine = member(dm, user(ME, "나"), 40L);
            when(chatRoomMemberRepository.findAllByUserId(ME)).thenReturn(List.of(mine));
            when(chatRoomMemberRepository.findAllByRoomId(ROOM_ID))
              .thenReturn(List.of(mine, member(dm, user(PARTNER, "상대"), null)));
            when(chatMessageRepository.findFirstByRoomIdOrderByIdDesc(ROOM_ID)).thenReturn(Optional.empty());
            when(chatMessageRepository.countByRoomIdAndIdGreaterThan(ROOM_ID, 40L)).thenReturn(3L);

            assertThat(chatService.getMyRooms(ME)).singleElement()
              .extracting(ChatRoomResponse::getUnreadCount).isEqualTo(3L);

            verify(chatMessageRepository, never()).countByRoomId(anyLong());
        }

        @Test
        @DisplayName("DM 방의 제목과 partnerId 는 '나 아닌 멤버' 에서 온다")
        void dmTitleComesFromPartner() {
            ChatRoom dm = room(RoomType.DM, ROOM_ID);
            ChatRoomMember mine = member(dm, user(ME, "나"), null);
            when(chatRoomMemberRepository.findAllByUserId(ME)).thenReturn(List.of(mine));
            when(chatRoomMemberRepository.findAllByRoomId(ROOM_ID))
              .thenReturn(List.of(mine, member(dm, user(PARTNER, "김상대"), null)));
            when(chatMessageRepository.findFirstByRoomIdOrderByIdDesc(ROOM_ID)).thenReturn(Optional.empty());

            ChatRoomResponse response = chatService.getMyRooms(ME).get(0);

            assertThat(response.getTitle()).isEqualTo("김상대");
            assertThat(response.getPartnerId()).isEqualTo(PARTNER);
            assertThat(response.getType()).isEqualTo("DM");
        }

        @Test
        @DisplayName("GROUP 방은 방 이름을 유지하고 partnerId 가 없다 (멤버를 훑지도 않는다)")
        void groupRoomKeepsItsTitle() {
            ChatRoom group = TestEntities.withId(
              ChatRoom.builder().type(RoomType.GROUP).teamId(9L).title("백엔드 팀").build(), ROOM_ID);
            group.touch();
            ChatRoomMember mine = member(group, user(ME, "나"), null);
            when(chatRoomMemberRepository.findAllByUserId(ME)).thenReturn(List.of(mine));
            when(chatMessageRepository.findFirstByRoomIdOrderByIdDesc(ROOM_ID)).thenReturn(Optional.empty());

            ChatRoomResponse response = chatService.getMyRooms(ME).get(0);

            assertThat(response.getTitle()).isEqualTo("백엔드 팀");
            assertThat(response.getPartnerId()).isNull();
            assertThat(response.getTeamId()).isEqualTo(9L);
            verify(chatRoomMemberRepository, never()).findAllByRoomId(anyLong());
        }

        @Test
        @DisplayName("최근 대화가 먼저 온다 (lastMessageAt 내림차순)")
        void sortedByLastMessageDesc() {
            ChatRoom older = TestEntities.withId(ChatRoom.builder().type(RoomType.GROUP).title("오래된").build(), 1L);
            ChatRoom newer = TestEntities.withId(ChatRoom.builder().type(RoomType.GROUP).title("최근").build(), 2L);
            older.touch();
            newer.touch();

            when(chatRoomMemberRepository.findAllByUserId(ME))
              .thenReturn(List.of(member(older, user(ME, "나"), null), member(newer, user(ME, "나"), null)));
            when(chatMessageRepository.findFirstByRoomIdOrderByIdDesc(1L))
              .thenReturn(Optional.of(messageAt(older, LocalDateTime.now().minusDays(3))));
            when(chatMessageRepository.findFirstByRoomIdOrderByIdDesc(2L))
              .thenReturn(Optional.of(messageAt(newer, LocalDateTime.now())));

            assertThat(chatService.getMyRooms(ME))
              .extracting(ChatRoomResponse::getTitle)
              .containsExactly("최근", "오래된");
        }

        @Test
        @DisplayName("메시지가 없는 방은 방의 updatedAt 을 마지막 시각으로 쓴다")
        void fallsBackToRoomUpdatedAt() {
            ChatRoom empty = TestEntities.withId(ChatRoom.builder().type(RoomType.GROUP).title("빈 방").build(), ROOM_ID);
            empty.touch();
            when(chatRoomMemberRepository.findAllByUserId(ME))
              .thenReturn(List.of(member(empty, user(ME, "나"), null)));
            when(chatMessageRepository.findFirstByRoomIdOrderByIdDesc(ROOM_ID)).thenReturn(Optional.empty());

            ChatRoomResponse response = chatService.getMyRooms(ME).get(0);

            assertThat(response.getLastMessage()).isNull();
            assertThat(response.getLastMessageAt()).isEqualTo(empty.getUpdatedAt());
        }
    }

    @Nested
    @DisplayName("메시지 이력")
    class GetMessages {

        @Test
        @DisplayName("조회는 최신순이지만 반환은 오래된→최신으로 뒤집는다 (화면 표시 순서)")
        void reversesToChronologicalOrder() {
            givenMembership();
            ChatRoom room = room(RoomType.DM, ROOM_ID);
            when(chatMessageRepository.findByRoomIdOrderByIdDesc(eq(ROOM_ID), any(Pageable.class)))
              .thenReturn(List.of(message(room, 30L, "셋째"), message(room, 20L, "둘째"), message(room, 10L, "첫째")));

            assertThat(chatService.getMessages(ME, ROOM_ID, null, 30))
              .extracting(ChatMessageResponse::getContent)
              .containsExactly("첫째", "둘째", "셋째");
        }

        @Test
        @DisplayName("before 가 없으면 초기 로드 쿼리를, 있으면 과거 더보기 쿼리를 쓴다")
        void picksQueryByBeforeId() {
            givenMembership();
            when(chatMessageRepository.findByRoomIdOrderByIdDesc(eq(ROOM_ID), any())).thenReturn(List.of());
            when(chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(eq(ROOM_ID), eq(50L), any()))
              .thenReturn(List.of());

            chatService.getMessages(ME, ROOM_ID, null, 30);
            verify(chatMessageRepository).findByRoomIdOrderByIdDesc(eq(ROOM_ID), any());

            chatService.getMessages(ME, ROOM_ID, 50L, 30);
            verify(chatMessageRepository).findByRoomIdAndIdLessThanOrderByIdDesc(eq(ROOM_ID), eq(50L), any());
        }

        @Test
        @DisplayName("size 는 첫 페이지 크기로 그대로 전달된다")
        void passesPageSize() {
            givenMembership();
            when(chatMessageRepository.findByRoomIdOrderByIdDesc(eq(ROOM_ID), any())).thenReturn(List.of());

            chatService.getMessages(ME, ROOM_ID, null, 7);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(chatMessageRepository).findByRoomIdOrderByIdDesc(eq(ROOM_ID), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isZero();
            assertThat(pageable.getValue().getPageSize()).isEqualTo(7);
        }

        @Test
        @DisplayName("없는 방은 CHAT_ROOM_NOT_FOUND, 있는 방의 외부인은 NOT_ROOM_MEMBER 로 구분한다")
        void distinguishesMissingRoomFromNonMember() {
            when(chatRoomRepository.existsById(ROOM_ID)).thenReturn(false);
            assertThatThrownBy(() -> chatService.getMessages(ME, ROOM_ID, null, 30))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

            when(chatRoomRepository.existsById(ROOM_ID)).thenReturn(true);
            when(chatRoomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, ME)).thenReturn(false);
            assertThatThrownBy(() -> chatService.getMessages(ME, ROOM_ID, null, 30))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.NOT_ROOM_MEMBER);
        }

        private void givenMembership() {
            when(chatRoomRepository.existsById(ROOM_ID)).thenReturn(true);
            when(chatRoomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, ME)).thenReturn(true);
        }
    }

    @Nested
    @DisplayName("읽음 처리")
    class MarkAsRead {

        @Test
        @DisplayName("읽은 위치를 앞으로 옮긴다")
        void movesForward() {
            ChatRoomMember membership = member(room(RoomType.DM, ROOM_ID), user(ME, "나"), 10L);
            when(chatRoomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ME)).thenReturn(Optional.of(membership));

            chatService.markAsRead(ME, ROOM_ID, 25L);

            assertThat(membership.getLastReadMessageId()).isEqualTo(25L);
        }

        @Test
        @DisplayName("뒤로 가는 값은 무시한다 — 늦게 도착한 옛 요청이 안읽음 수를 되살리면 안 된다")
        void ignoresBackwardsUpdate() {
            ChatRoomMember membership = member(room(RoomType.DM, ROOM_ID), user(ME, "나"), 25L);
            when(chatRoomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ME)).thenReturn(Optional.of(membership));

            chatService.markAsRead(ME, ROOM_ID, 10L);

            assertThat(membership.getLastReadMessageId()).isEqualTo(25L);
        }

        @Test
        @DisplayName("null 도 무시한다")
        void ignoresNull() {
            ChatRoomMember membership = member(room(RoomType.DM, ROOM_ID), user(ME, "나"), 25L);
            when(chatRoomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ME)).thenReturn(Optional.of(membership));

            chatService.markAsRead(ME, ROOM_ID, null);

            assertThat(membership.getLastReadMessageId()).isEqualTo(25L);
        }

        @Test
        @DisplayName("멤버가 아니면 NOT_ROOM_MEMBER")
        void nonMember() {
            when(chatRoomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ME)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> chatService.markAsRead(ME, ROOM_ID, 1L))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.NOT_ROOM_MEMBER);
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    private void givenBothUsersExist() {
        when(userRepository.findById(ME)).thenReturn(Optional.of(user(ME, "나")));
        when(userRepository.findById(PARTNER)).thenReturn(Optional.of(user(PARTNER, "상대")));
    }

    /**
     * 발신자가 멤버인 방을 세팅하고, 저장된 메시지에 id 77 이 붙도록 만든다.
     */
    private ChatRoomMember givenSenderIsMember() {
        ChatRoom dm = room(RoomType.DM, ROOM_ID);
        ChatRoomMember senderMembership = member(dm, user(ME, "나"), null);
        ChatRoomMember partnerMembership = member(dm, user(PARTNER, "상대"), null);

        when(chatRoomMemberRepository.findByRoomIdAndUserId(ROOM_ID, ME))
          .thenReturn(Optional.of(senderMembership));
        when(chatRoomMemberRepository.findAllByRoomId(ROOM_ID))
          .thenReturn(List.of(senderMembership, partnerMembership));
        // 서비스가 save() 반환값의 id 를 읽으므로, 저장된 그 인스턴스에 id 를 붙여 돌려준다.
        when(chatMessageRepository.save(any())).thenAnswer(invocation
          -> TestEntities.withId(invocation.getArgument(0), 77L));

        return senderMembership;
    }

    private User user(Long id, String name) {
        return User.builder().id(id).name(name).build();
    }

    private ChatRoom room(RoomType type, Long id) {
        ChatRoom room = TestEntities.withId(ChatRoom.builder().type(type).build(), id);
        room.touch();
        return room;
    }

    private ChatRoomMember member(ChatRoom room, User user, Long lastReadMessageId) {
        ChatRoomMember membership = ChatRoomMember.builder().room(room).user(user).build();
        membership.updateLastReadMessageId(lastReadMessageId);
        return membership;
    }

    private ChatMessage message(ChatRoom room, Long id, String content) {
        return TestEntities.withId(
          ChatMessage.builder().room(room).sender(user(PARTNER, "상대")).content(content).build(), id);
    }

    private ChatMessage messageAt(ChatRoom room, LocalDateTime createdAt) {
        ChatMessage message = message(room, 1L, "내용");
        return TestEntities.withField(message, "createdAt", createdAt);
    }
}

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
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationService notificationService;

    // -------------------------------------------------------------------------
    // 1. DM 방 조회-or-생성 (멱등)
    // -------------------------------------------------------------------------
    @Transactional
    public ChatRoom getOrCreateDmRoom(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new MateonException(ErrorCode.CANNOT_CHAT_WITH_SELF);
        }

        User me = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        // 기존 DM 방이 있으면 그대로 반환
        return chatRoomRepository.findDmRoom(RoomType.DM, userId, targetUserId)
                .orElseGet(() -> {
                    ChatRoom room = chatRoomRepository.save(
                            ChatRoom.builder().type(RoomType.DM).build());
                    chatRoomMemberRepository.save(ChatRoomMember.builder().room(room).user(me).build());
                    chatRoomMemberRepository.save(ChatRoomMember.builder().room(room).user(target).build());
                    return room;
                });
    }

    // -------------------------------------------------------------------------
    // 2. 메시지 저장 + 실시간 브로드캐스트 + 알림 (persist-then-push)
    // -------------------------------------------------------------------------
    @Transactional
    public ChatMessageResponse saveAndBroadcast(Long senderId, Long roomId, String content) {
        if (content == null || content.isBlank()) {
            throw new MateonException(ErrorCode.INVALID_INPUT, "메시지 내용이 비어 있습니다.");
        }

        ChatRoomMember senderMembership = chatRoomMemberRepository.findByRoomIdAndUserId(roomId, senderId)
                .orElseThrow(() -> new MateonException(ErrorCode.NOT_ROOM_MEMBER));

        ChatRoom room = senderMembership.getRoom();
        User sender = senderMembership.getUser();

        // A. DB 저장
        ChatMessage message = chatMessageRepository.save(
                ChatMessage.builder().room(room).sender(sender).content(content).build());

        // 발신자는 자기 메시지를 곧바로 읽은 것으로 처리 + 방 최신순 정렬 갱신
        senderMembership.updateLastReadMessageId(message.getId());
        room.touch();

        ChatMessageResponse response = new ChatMessageResponse(message);

        // B. 방 구독자에게 실시간 브로드캐스트
        messagingTemplate.convertAndSend("/topic/room." + roomId, response);

        // C. 발신자를 제외한 나머지 멤버에게 알림 (기존 SSE 알림 시스템 연동)
        String preview = content.length() > 30 ? content.substring(0, 30) + "…" : content;
        for (ChatRoomMember member : chatRoomMemberRepository.findAllByRoomId(roomId)) {
            if (member.getUser().getId().equals(senderId)) {
                continue;
            }
            notificationService.send(
                    member.getUser(),
                    sender.getName() + "님의 메시지",
                    preview,
                    Notification.NotificationType.INFO);
        }

        return response;
    }

    // -------------------------------------------------------------------------
    // 3. 내가 참여한 방 목록 (최신 대화순 + 안읽음 수)
    // -------------------------------------------------------------------------
    @Transactional(readOnly = true)
    public List<ChatRoomResponse> getMyRooms(Long userId) {
        List<ChatRoomResponse> result = new ArrayList<>();

        for (ChatRoomMember membership : chatRoomMemberRepository.findAllByUserId(userId)) {
            ChatRoom room = membership.getRoom();

            // 마지막 메시지 미리보기
            Optional<ChatMessage> last = chatMessageRepository.findFirstByRoomIdOrderByIdDesc(room.getId());

            // 안읽음 수
            long unread = (membership.getLastReadMessageId() == null)
                    ? chatMessageRepository.countByRoomId(room.getId())
                    : chatMessageRepository.countByRoomIdAndIdGreaterThan(room.getId(), membership.getLastReadMessageId());

            // DM 이면 상대방 정보로 title/partnerId 채우기
            String title = room.getTitle();
            Long partnerId = null;
            if (room.getType() == RoomType.DM) {
                for (ChatRoomMember other : chatRoomMemberRepository.findAllByRoomId(room.getId())) {
                    if (!other.getUser().getId().equals(userId)) {
                        partnerId = other.getUser().getId();
                        title = other.getUser().getName();
                        break;
                    }
                }
            }

            result.add(ChatRoomResponse.builder()
                    .room(room)
                    .title(title)
                    .partnerId(partnerId)
                    .lastMessage(last.map(ChatMessage::getContent).orElse(null))
                    .lastMessageAt(last.map(ChatMessage::getCreatedAt).orElse(room.getUpdatedAt()))
                    .unreadCount(unread)
                    .build());
        }

        // 최신 대화순 정렬 (lastMessageAt desc)
        result.sort(Comparator.comparing(ChatRoomResponse::getLastMessageAt).reversed());
        return result;
    }

    // -------------------------------------------------------------------------
    // 4. 메시지 이력 조회 (페이징: before 이전 size 건, 오래된→최신 순으로 반환)
    // -------------------------------------------------------------------------
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(Long userId, Long roomId, Long beforeId, int size) {
        validateMembership(roomId, userId);

        PageRequest pageable = PageRequest.of(0, size);
        List<ChatMessage> messages = (beforeId == null)
                ? chatMessageRepository.findByRoomIdOrderByIdDesc(roomId, pageable)
                : chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(roomId, beforeId, pageable);

        // 조회는 최신순(desc)이지만 화면 표시는 오래된→최신이 자연스러우므로 뒤집어 반환
        List<ChatMessageResponse> result = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            result.add(new ChatMessageResponse(messages.get(i)));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 5. 읽음 처리
    // -------------------------------------------------------------------------
    @Transactional
    public void markAsRead(Long userId, Long roomId, Long lastReadMessageId) {
        ChatRoomMember membership = chatRoomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new MateonException(ErrorCode.NOT_ROOM_MEMBER));
        membership.updateLastReadMessageId(lastReadMessageId);
    }

    // -------------------------------------------------------------------------
    // 공통: 방 존재 + 멤버십 검증
    // -------------------------------------------------------------------------
    private void validateMembership(Long roomId, Long userId) {
        if (!chatRoomRepository.existsById(roomId)) {
            throw new MateonException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        if (!chatRoomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) {
            throw new MateonException(ErrorCode.NOT_ROOM_MEMBER);
        }
    }
}

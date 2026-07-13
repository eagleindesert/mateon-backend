package com.example.mateon.chat.controller;

import com.example.mateon.chat.domain.ChatRoom;
import com.example.mateon.chat.dto.request.CreateDmRequest;
import com.example.mateon.chat.dto.request.ReadRequest;
import com.example.mateon.chat.dto.response.ChatMessageResponse;
import com.example.mateon.chat.dto.response.ChatRoomResponse;
import com.example.mateon.chat.service.ChatService;
import com.example.mateon.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatRestController {

    private final ChatService chatService;

    // DM 방 조회-or-생성 (멱등)
    @PostMapping("/rooms/dm")
    public ApiResponse<Map<String, Long>> createOrGetDmRoom(@Valid @RequestBody CreateDmRequest request,
                                                            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        ChatRoom room = chatService.getOrCreateDmRoom(userId, request.getTargetUserId());
        return ApiResponse.success(Map.of("roomId", room.getId()));
    }

    // 내가 참여한 방 목록 (마지막 메시지 미리보기 + 안읽음 수)
    @GetMapping("/rooms")
    public ApiResponse<List<ChatRoomResponse>> getMyRooms(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return ApiResponse.success(chatService.getMyRooms(userId));
    }

    // 메시지 이력 조회 (before 이전 size 건, 오래된→최신 순)
    @GetMapping("/rooms/{roomId}/messages")
    public ApiResponse<List<ChatMessageResponse>> getMessages(@PathVariable Long roomId,
                                                              @RequestParam(required = false) Long before,
                                                              @RequestParam(defaultValue = "30") int size,
                                                              Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return ApiResponse.success(chatService.getMessages(userId, roomId, before, size));
    }

    // 읽음 처리
    @PostMapping("/rooms/{roomId}/read")
    public ApiResponse<Void> markAsRead(@PathVariable Long roomId,
                                        @Valid @RequestBody ReadRequest request,
                                        Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        chatService.markAsRead(userId, roomId, request.getLastReadMessageId());
        return ApiResponse.success(null);
    }
}

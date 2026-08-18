package com.example.mateon.chat.controller;

import com.example.mateon.chat.domain.ChatRoom;
import com.example.mateon.chat.dto.request.CreateDmRequest;
import com.example.mateon.chat.dto.request.ReadRequest;
import com.example.mateon.chat.dto.response.ChatMessageResponse;
import com.example.mateon.chat.dto.response.ChatRoomResponse;
import com.example.mateon.chat.service.ChatService;
import com.example.mateon.common.dto.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "채팅 (REST)", description = "채팅방 목록·메시지 이력·읽음 처리. 실시간 송수신은 STOMP(/ws-stomp)로 한다")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatRestController {

    private final ChatService chatService;

    // DM 방 조회-or-생성 (멱등)
    @Operation(summary = "DM 방 조회 또는 생성",
      description = """
                    상대와의 1:1 방을 돌려준다. **이미 있으면 그 방을, 없으면 새로 만들어**
                    roomId 를 준다 — 여러 번 불러도 방이 늘지 않으므로(멱등) "방이 있는지" 먼저
                    확인할 필요가 없다.

                    받은 roomId 로 이력을 조회하고, 실제 송수신은 STOMP(`/ws-stomp`)로 한다.""")
    @ApiResponse(responseCode = "400",
      description = "CANNOT_CHAT_WITH_SELF — 자기 자신과는 채팅할 수 없습니다.")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 상대 사용자를 찾을 수 없습니다.")
    @PostMapping("/rooms/dm")
    public BaseResponse<Map<String, Long>> createOrGetDmRoom(@Valid @RequestBody CreateDmRequest request,
                                                             Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        ChatRoom room = chatService.getOrCreateDmRoom(userId, request.getTargetUserId());
        return BaseResponse.success(Map.of("roomId", room.getId()));
    }

    // 내가 참여한 방 목록 (마지막 메시지 미리보기 + 안읽음 수)
    @Operation(summary = "내 채팅방 목록",
      description = """
                    채팅 목록 화면용이다. 방마다 마지막 메시지 미리보기와 안 읽은 개수가 함께
                    실려 있어 방을 열지 않고도 목록을 그릴 수 있다.

                    참여한 방이 없으면 빈 배열이다.""")
    @GetMapping("/rooms")
    public BaseResponse<List<ChatRoomResponse>> getMyRooms(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return BaseResponse.success(chatService.getMyRooms(userId));
    }

    // 메시지 이력 조회 (before 이전 size 건, 오래된→최신 순)
    @Operation(summary = "메시지 이력 조회",
      description = """
                    **위로 스크롤하며 과거를 불러오는 방식**이다. 처음에는 before 없이 불러
                    최근 size 건을 받고, 더 올라갈 때는 받은 배열의 **첫 번째 메시지 id** 를
                    before 로 넘긴다.

                    조회는 최신순이지만 **배열은 오래된→최신 순으로 뒤집어** 내려간다.
                    그대로 화면에 그리면 된다.

                    받은 배열이 size 보다 짧으면 더 이상 과거가 없다는 뜻이다.""")
    @ApiResponse(responseCode = "400", description = """
            NOT_ROOM_MEMBER — 해당 채팅방의 참여자가 아닙니다.
            CHAT_ROOM_NOT_FOUND — 채팅방을 찾을 수 없습니다.""")
    @Parameter(name = "roomId", description = "조회할 방. 내가 참여 중인 방이어야 한다.")
    @Parameter(name = "before", description = "이 메시지 id 보다 과거만 가져온다. 생략하면 최신부터.")
    @Parameter(name = "size", description = "한 번에 가져올 건수.")
    @GetMapping("/rooms/{roomId}/messages")
    public BaseResponse<List<ChatMessageResponse>> getMessages(@PathVariable Long roomId,
                                                               @RequestParam(required = false) Long before,
                                                               @RequestParam(defaultValue = "30") int size,
                                                               Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return BaseResponse.success(chatService.getMessages(userId, roomId, before, size));
    }

    // 읽음 처리
    @Operation(summary = "읽음 처리",
      description = """
                    어디까지 읽었는지를 lastReadMessageId 로 표시한다. 이 값이 방 목록의
                    안 읽은 개수를 계산하는 기준이다.

                    보통 방을 열거나 새 메시지를 받았을 때 화면에 보인 마지막 메시지 id 로 부른다.""")
    @ApiResponse(responseCode = "400",
      description = "NOT_ROOM_MEMBER — 해당 채팅방의 참여자가 아닙니다.")
    @Parameter(name = "roomId", description = "읽음을 표시할 방.")
    @PostMapping("/rooms/{roomId}/read")
    public BaseResponse<Void> markAsRead(@PathVariable Long roomId,
                                         @Valid @RequestBody ReadRequest request,
                                         Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        chatService.markAsRead(userId, roomId, request.getLastReadMessageId());
        return BaseResponse.success(null);
    }
}

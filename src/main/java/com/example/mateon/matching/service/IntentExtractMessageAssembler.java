package com.example.mateon.matching.service;

import com.example.mateon.aichat.domain.AiChatMessage;
import com.example.mateon.aichat.domain.IntentPrefixKind;
import com.example.mateon.matching.client.intent.IntentExtractRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 작업 로그를 FastAPI /intents/extract 배열 순서로 바꾼다.
 *
 * <p>
 * DB seq 는 보낸 시점이라 접두가 사용자 첫 말보다 뒤에 붙어 있을 수 있다. 요청 본문만
 * 명세대로 접두를 앞에 둔다. 재추출 assistant 는 client_visible=false 라 본편에 안 넣는다.
 */
public final class IntentExtractMessageAssembler {

    private IntentExtractMessageAssembler() {
    }

    public static List<IntentExtractRequest.Message> assemble(List<AiChatMessage> taskMessages) {
        List<IntentExtractRequest.Message> messages = new ArrayList<>(taskMessages.size());
        addPrefix(taskMessages, IntentPrefixKind.PROFILE, messages);
        addPrefix(taskMessages, IntentPrefixKind.PORTFOLIO, messages);
        for (AiChatMessage message : taskMessages) {
            if (message.isClientVisible()) {
                messages.add(IntentExtractRequest.Message.of(
                  message.getRole().toExtractRole(), message.getContent()));
            }
        }
        return messages;
    }

    private static void addPrefix(List<AiChatMessage> taskMessages, IntentPrefixKind kind,
      List<IntentExtractRequest.Message> target) {
        for (AiChatMessage message : taskMessages) {
            if (message.getPrefixKind() == kind) {
                target.add(IntentExtractRequest.Message.user(message.getContent()));
                return;
            }
        }
    }
}

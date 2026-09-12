package com.example.mateon.matching.service;

import com.example.mateon.aichat.domain.AiChatMessage;
import com.example.mateon.aichat.domain.AiChatRole;
import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.domain.IntentPrefixKind;
import com.example.mateon.matching.client.intent.IntentExtractRequest;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentExtractMessageAssemblerTest {

    @Test
    @DisplayName("접두는 seq 가 뒤여도 요청 배열 앞에 오고, 본편은 seq 순이다")
    void prefixesLeadRegardlessOfSeq() {
        AiChatSession session = new AiChatSession(User.builder().name("김").build());
        List<IntentExtractRequest.Message> messages = IntentExtractMessageAssembler.assemble(List.of(
          new AiChatMessage(session, 1, AiChatRole.USER, "프론트엔드요"),
          new AiChatMessage(session, 2, AiChatRole.ASSISTANT, "알겠어"),
          new AiChatMessage(session, 3, AiChatRole.USER, "[자기소개서]\n전공: 컴공",
            IntentPrefixKind.PROFILE),
          new AiChatMessage(session, 4, AiChatRole.USER, "[포트폴리오]\nCRUD",
            IntentPrefixKind.PORTFOLIO)));

        assertThat(messages).extracting(IntentExtractRequest.Message::getRole)
          .containsExactly("user", "user", "user", "assistant");
        assertThat(messages).extracting(IntentExtractRequest.Message::getMessage)
          .containsExactly(
            "[자기소개서]\n전공: 컴공",
            "[포트폴리오]\nCRUD",
            "프론트엔드요",
            "알겠어");
    }

    @Test
    @DisplayName("재추출 assistant 는 요청 배열에 안 넣는다 — 완료 문구가 턴마다 쌓이면 안 된다")
    void hiddenAssistantIsExcluded() {
        AiChatSession session = new AiChatSession(User.builder().name("김").build());
        List<IntentExtractRequest.Message> messages = IntentExtractMessageAssembler.assemble(List.of(
          new AiChatMessage(session, 1, AiChatRole.USER, "프론트엔드요"),
          new AiChatMessage(session, 2, AiChatRole.ASSISTANT, "알겠어"),
          new AiChatMessage(session, 3, AiChatRole.USER, "[자기소개서]\n전공: 컴공",
            IntentPrefixKind.PROFILE),
          new AiChatMessage(session, 4, AiChatRole.ASSISTANT, "슬롯을 다시 정리했어요",
            null, false)));

        assertThat(messages).extracting(IntentExtractRequest.Message::getMessage)
          .containsExactly(
            "[자기소개서]\n전공: 컴공",
            "프론트엔드요",
            "알겠어");
    }
}

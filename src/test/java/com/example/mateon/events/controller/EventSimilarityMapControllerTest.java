package com.example.mateon.events.controller;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.dto.ContestSimilarityMapResponseDTO;
import com.example.mateon.events.dto.ContestSimilarityMapResponseDTO.Point;
import com.example.mateon.events.dto.ContestSimilarityMapResponseDTO.Query;
import com.example.mateon.events.service.EventExtractionService;
import com.example.mateon.events.service.EventService;
import com.example.mateon.events.service.EventSimilarityMapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventSimilarityMapControllerTest {

    private MockMvc mockMvc;
    private EventSimilarityMapService similarityMapService;

    @BeforeEach
    void setUp() {
        similarityMapService = mock(EventSimilarityMapService.class);
        mockMvc = MockMvcBuilders
          .standaloneSetup(new EventController(
            mock(EventService.class), mock(EventExtractionService.class), similarityMapService))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();
    }

    @Test
    @DisplayName("200 에 camelCase 필드가 실리고 기본 topN 은 500 이다")
    void returnsMapWithDefaultTopN() throws Exception {
        when(similarityMapService.map(eq(10L), eq(500))).thenReturn(sample());

        mockMvc.perform(get("/api/events/10/similarity-map"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.data.query.id").value(10))
          .andExpect(jsonPath("$.data.query.fieldLabel").value("교육"))
          .andExpect(jsonPath("$.data.points[0].id").value(11))
          .andExpect(jsonPath("$.data.points[0].rankPercentile").value(0.0))
          .andExpect(jsonPath("$.data.maxRadius").value(12.0))
          .andExpect(jsonPath("$.data.minRadius").value(2.6))
          .andExpect(jsonPath("$.data.radialJitter").value(0.5))
          .andExpect(jsonPath("$.data.referenceRings[0].percentile").value(0.1))
          .andExpect(jsonPath("$.data.candidatePoolTotal").value(1));

        verify(similarityMapService).map(10L, 500);
    }

    @Test
    @DisplayName("topN=0 도 서비스로 그대로 넘긴다 (클램핑은 서비스 몫)")
    void forwardsTopN() throws Exception {
        when(similarityMapService.map(eq(10L), eq(0))).thenReturn(sample());

        mockMvc.perform(get("/api/events/10/similarity-map").param("topN", "0"))
          .andExpect(status().isOk());

        verify(similarityMapService).map(10L, 0);
    }

    @Test
    @DisplayName("없는 활동은 404 EVENT_NOT_FOUND")
    void missingEventIs404() throws Exception {
        when(similarityMapService.map(eq(99L), eq(500)))
          .thenThrow(new MateonException(ErrorCode.EVENT_NOT_FOUND));

        mockMvc.perform(get("/api/events/99/similarity-map"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.message").value("활동을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("임베딩이 아직이면 400 EVENT_EMBEDDING_NOT_READY")
    void embeddingNotReadyIs400() throws Exception {
        when(similarityMapService.map(eq(10L), eq(500)))
          .thenThrow(new MateonException(ErrorCode.EVENT_EMBEDDING_NOT_READY));

        mockMvc.perform(get("/api/events/10/similarity-map"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.message").value(
            "공모전 정보 분석이 아직 완료되지 않았습니다. 잠시 후 다시 시도해주세요."));
    }

    private static ContestSimilarityMapResponseDTO sample() {
        return new ContestSimilarityMapResponseDTO(
          new Query(10L, "기준", "주최", "CONTEST", "EDUCATION", "교육", null),
          List.of(new Point(11L, "후보", "한솔", "CONTEST", "PLANNING_IDEA", "기획/아이디어",
            null, 0.615, 0.0, 2.6, -2.737, 0.025)),
          12.0,
          2.6,
          0.5,
          List.of(new ContestSimilarityMapResponseDTO.ReferenceRing(0.1, 0.615, 3.54)),
          1);
    }
}

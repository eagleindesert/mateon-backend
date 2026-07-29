package com.example.mateon.portfolios.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 포트폴리오 PDF 요약 응답.
 *
 * <p>필드가 하나뿐인 이유: AI 가 주는 결과물 자체가 고정 스키마가 아니라 "불릿 목록 + 요약 문단"으로
 * 이어지는 마크다운 문자열 하나다. 사람이 읽는 자유 형식이라 더 쪼갤 축이 없다.
 *
 * <p>pdf_id(SHA-256)는 내보내지 않는다. 백엔드가 중복 업로드를 판정하는 내부 식별자이고,
 * 프론트가 그 값으로 할 일이 없다.
 */
@Getter
@AllArgsConstructor
public class PortfolioSummaryResponseDTO {

    private final String summary;
}

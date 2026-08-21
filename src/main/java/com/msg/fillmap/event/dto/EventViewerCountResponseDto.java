package com.msg.fillmap.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 행사방 현재 열람 인원 응답 (MSG-443).
 *
 * @param viewerCount 현재 열람 인원. 0 = 아무도 없음(표시), null = 캐시 장애(FE 가 문구를 숨긴다)
 */
@Schema(description = "행사방 현재 열람 인원 응답.", requiredProperties = "viewerCount")
public record EventViewerCountResponseDto(
	@Schema(description = "현재 열람 인원 — 마지막 heartbeat 가 90초 이내인 고유 세션 수. "
		+ "0 은 아무도 없음(표시), null 은 캐시 장애(숨김)", example = "120", nullable = true)
	Integer viewerCount
) {
}

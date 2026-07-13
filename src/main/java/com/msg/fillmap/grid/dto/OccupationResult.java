package com.msg.fillmap.grid.dto;

/**
 * {@link com.msg.fillmap.grid.service.GridOccupationService#occupy} 반환 — 서비스 간 내부 반환 객체.
 * HTTP 경계 DTO가 아니므로 {@code XxxResponseDto} 접미사를 붙이지 않는다 (HTTP 응답 DTO는 MSG-73).
 */
public record OccupationResult(String gridId, boolean isFirstOccupation, int videoCount) {
}

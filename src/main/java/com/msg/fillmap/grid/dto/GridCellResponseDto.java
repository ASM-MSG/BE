package com.msg.fillmap.grid.dto;

/**
 * 단일 격자 색칠 상태 응답. occupied = 내 점령 여부(user_grids row 존재), videoCount = 내 영상 수.
 * 미점령이면 occupied=false, videoCount=0 (격자는 항상 존재하는 논리 개념 — 404 아님).
 */
public record GridCellResponseDto(String gridId, boolean occupied, Integer videoCount) {
}

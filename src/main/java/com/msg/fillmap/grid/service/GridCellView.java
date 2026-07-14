package com.msg.fillmap.grid.service;

/**
 * 단일 격자 색칠 상태 내부 뷰 (서비스 간 계약). HTTP 응답 DTO 로의 변환은 컨트롤러가 한다.
 */
public record GridCellView(String gridId, boolean occupied, int videoCount) {
}

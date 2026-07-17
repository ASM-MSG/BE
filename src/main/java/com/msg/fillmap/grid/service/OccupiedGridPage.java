package com.msg.fillmap.grid.service;

import java.util.List;

/**
 * viewport 점령 격자 페이지 내부 뷰 (MSG-90, 서비스 간 계약). nextCursor 는 opaque 토큰(GridCursor 인코딩)
 * 이고 마지막 페이지면 null 이다. HTTP 응답 DTO(OccupiedGridPageResponseDto) 변환은 컨트롤러 책임.
 */
public record OccupiedGridPage(List<OccupiedGridView> items, String nextCursor) {
}

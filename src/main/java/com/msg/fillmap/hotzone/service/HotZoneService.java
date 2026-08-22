package com.msg.fillmap.hotzone.service;

import java.util.List;

import com.msg.fillmap.grid.dto.ViewportBounds;

// com.msg.fillmap.hotzone.service — Owner A 제공
public interface HotZoneService {

	/**
	 * viewport 안 핫구역 목록 — 핫스코어 내림차순, 상위 K·최소 임계 적용 후 뷰포트 필터.
	 * 없으면 빈 목록(에러 아님). 뒤집힌 bbox → HotZoneErrorCode.INVALID_VIEWPORT.
	 * userId 를 받지 않는다 — 집계 결과는 사용자 무관(개인화 없음). 조회는 비로그인도 허용된다
	 * (MSG-454, 상단 칩은 비로그인 원칙 — SecurityConfig 의 GET permitAll).
	 */
	List<HotZoneView> getHotZones(ViewportBounds bounds);
}

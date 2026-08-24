package com.msg.fillmap.hotzone.service;

import java.util.List;

import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.hotzone.dto.HotZoneRegionAggregateResponseDto;

// com.msg.fillmap.hotzone.service — Owner A 제공
public interface HotZoneService {

	/**
	 * viewport 안 핫구역 목록 — 핫스코어 내림차순, 상위 K·최소 임계 적용 후 뷰포트 필터.
	 * 없으면 빈 목록(에러 아님). 뒤집힌 bbox → HotZoneErrorCode.INVALID_VIEWPORT.
	 * userId 를 받지 않는다 — 집계 결과는 사용자 무관(개인화 없음). 조회는 비로그인도 허용된다
	 * (MSG-454, 상단 칩은 비로그인 원칙 — SecurityConfig 의 GET permitAll).
	 */
	List<HotZoneView> getHotZones(ViewportBounds bounds);

	/**
	 * viewport 안 핫 격자를 행정 단위(동, 시군구, 시도)로 묶어 센 집계 목록 (MSG-466).
	 * 집계 대상은 getHotZones 와 같은 핫 판정 집합이다 (상위 K, 최소 임계, 뷰포트 필터 공유).
	 * region_code 미판정 격자는 키와 이름이 null 인 항목 하나로 마지막에 실린다.
	 * bbox 상한은 단위별 차등(RegionUnit.maxSpanDeg), 초과 시 VIEWPORT_TOO_LARGE.
	 * 사용자 무관 응답이라 userId 를 받지 않는다. 비로그인 조회 허용 (GET permitAll).
	 */
	List<HotZoneRegionAggregateResponseDto> getHotZoneAggregates(ViewportBounds bounds, RegionUnit unit);
}

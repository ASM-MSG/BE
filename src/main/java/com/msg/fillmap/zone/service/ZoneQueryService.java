package com.msg.fillmap.zone.service;

import java.util.List;

import com.msg.fillmap.zone.dto.ZoneResponseDto;

/**
 * 구역 조회 계약 (MSG-234, Owner A 내부). 크로스오너 경계면 아님.
 * 장소 검색은 카카오 로컬 프록시(MSG-251)로 이관 — 이 서비스는 표시명용 목록만 낸다(ADR 축소, 2026-07-28).
 */
public interface ZoneQueryService {

	/** 전체 구역 목록(30~50행). 시딩 전이면 빈 리스트. */
	List<ZoneResponseDto> getZones();
}

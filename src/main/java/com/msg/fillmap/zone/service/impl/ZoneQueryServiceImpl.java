package com.msg.fillmap.zone.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.zone.dto.ZoneResponseDto;
import com.msg.fillmap.zone.repository.ZoneRepository;
import com.msg.fillmap.zone.service.ZoneQueryService;

/**
 * 구역 조회 구현 (MSG-234). geospatial 0 — findAll 전 컬럼 정수 로드뿐이다.
 * 장소 검색(2단 폴백)은 카카오 로컬 프록시(MSG-251) 이관으로 제거됐다(ADR 축소, 2026-07-28).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ZoneQueryServiceImpl implements ZoneQueryService {

	private final ZoneRepository zoneRepository;

	@Override
	public List<ZoneResponseDto> getZones() {
		return zoneRepository.findAll().stream().map(ZoneResponseDto::from).toList();
	}
}

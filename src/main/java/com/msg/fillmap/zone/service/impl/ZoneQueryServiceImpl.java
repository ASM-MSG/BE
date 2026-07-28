package com.msg.fillmap.zone.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.zone.dto.SearchResultResponseDto;
import com.msg.fillmap.zone.dto.ZoneResponseDto;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.repository.ZoneRepository;
import com.msg.fillmap.zone.service.ZoneQueryService;

/**
 * 구역 조회·검색 구현 (MSG-234). geospatial 0 — 목록·검색 zone 분기는 정수/파생 쿼리, regions 폴백만
 * RegionRepository 의 native ST_Envelope(zone 미매치일 때만). 두 리포지토리 다 Owner A 라 직접 주입한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ZoneQueryServiceImpl implements ZoneQueryService {

	private static final int MIN_LIMIT = 1;
	private static final int MAX_LIMIT = 50;

	private final ZoneRepository zoneRepository;
	private final RegionRepository regionRepository;

	@Override
	public List<ZoneResponseDto> getZones() {
		return zoneRepository.findAll().stream().map(ZoneResponseDto::from).toList();
	}

	@Override
	public List<SearchResultResponseDto> search(String q, int limit) {
		String query = q == null ? "" : q.trim();
		if (query.isEmpty()) {
			return List.of();
		}
		int capped = Math.min(Math.max(limit, MIN_LIMIT), MAX_LIMIT);

		List<Zone> zoneHits = zoneRepository.findByNameContainingIgnoreCaseOrderByPriorityDescZoneKeyAsc(query);
		if (!zoneHits.isEmpty()) {
			return zoneHits.stream().limit(capped).map(SearchResultResponseDto::ofZone).toList();
		}
		return regionRepository.searchByName(query, capped).stream()
			.map(SearchResultResponseDto::ofRegion).toList();
	}
}

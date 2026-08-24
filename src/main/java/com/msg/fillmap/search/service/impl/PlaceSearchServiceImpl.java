package com.msg.fillmap.search.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.search.dto.PlaceSearchResponseDto;
import com.msg.fillmap.search.service.KakaoLocalClient;
import com.msg.fillmap.search.service.PlaceSearchService;
import com.msg.fillmap.search.service.SearchKeywordCommandService;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 장소 검색 (MSG-251). @Transactional 없음 — 검색 경로 자체는 저장을 하지 않는다. 카카오 응답 무저장이
 * 약관 준수의 실체이고, 사용자가 입력한 검색어(q) 집계는 경계 안이다(MSG-258, 데브톡 150397).
 * 흐름 4단: trim 가드 → 검색어 집계 접수(비동기 fire-and-forget) → 카카오 호출·파싱(클라이언트) →
 * gridId 합성·주소 규칙(여기).
 */
@Service
@RequiredArgsConstructor
public class PlaceSearchServiceImpl implements PlaceSearchService {

	private final KakaoLocalClient kakaoLocalClient;
	private final SearchKeywordCommandService searchKeywordCommandService;
	private final ZoneNameQueryService zoneNameQueryService;

	@Override
	public List<PlaceSearchResponseDto> searchPlaces(long userId, String q) {
		String query = q.trim();
		if (query.isEmpty()) {
			// 빈 검색어는 집계 접수 전에 자른다 — 카카오 호출 0 은 위임받는 오버로드의 가드가 재보장한다
			return List.of();
		}
		// 카카오 호출 전 접수 — 호출 성공 여부와 무관하게 검색 의도를 집계한다 (MSG-258 §D1, FR-1)
		searchKeywordCommandService.recordSearch(userId, query);
		return searchPlaces(query);
	}

	@Override
	public List<PlaceSearchResponseDto> searchPlaces(String q) {
		// 집계 미접수 — 경로 추천의 기계 조립 검색어 전용, 인기 검색어 오염 방지 (MSG-457 계약 변경)
		String query = q.trim();
		if (query.isEmpty()) {
			// 빈 쿼리를 카카오에 흘리면 카카오가 400 을 내고 쿼터만 소모한다 — 호출 0 (§D3, 234 trim 가드 계승)
			return List.of();
		}
		// 리졸버는 매핑 진입 전 1회 — 결과마다 zones 를 다시 읽지 않는다 (MSG-341 FR-8)
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		return kakaoLocalClient.search(query).stream()
			.map(place -> {
				String gridId = GridEncoder.encode(place.lat(), place.lng());	// 즉석 계산 — 저장 아님
				GridIndex index = GridEncoder.decode(gridId);	// gridId 합성에 쓴 인덱스를 이름 산술에 재사용
				ZoneCellName name = resolver.name(index.gridY(), index.gridX());
				return new PlaceSearchResponseDto(
					place.placeName(),
					place.roadAddressName().isEmpty() ? place.addressName() : place.roadAddressName(),	// §D2 주소 규칙
					place.lat(),
					place.lng(),
					gridId,
					name.zoneName(),
					name.zoneCell());
			})
			.toList();
	}
}

package com.msg.fillmap.search.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.search.dto.PlaceSearchResponseDto;
import com.msg.fillmap.search.service.KakaoLocalClient;
import com.msg.fillmap.search.service.PlaceSearchService;

/**
 * 장소 검색 (MSG-251). @Transactional 없음 — DB·Redis 무접점이 약관 준수의 구조적 증거다(§D6).
 * 흐름 3단: trim 가드 → 카카오 호출·파싱(클라이언트) → gridId 합성·주소 규칙(여기).
 */
@Service
@RequiredArgsConstructor
public class PlaceSearchServiceImpl implements PlaceSearchService {

	private final KakaoLocalClient kakaoLocalClient;

	@Override
	public List<PlaceSearchResponseDto> searchPlaces(String q) {
		String query = q.trim();
		if (query.isEmpty()) {
			// 빈 쿼리를 카카오에 흘리면 카카오가 400 을 내고 쿼터만 소모한다 — 호출 0 (§D3, 234 trim 가드 계승)
			return List.of();
		}
		return kakaoLocalClient.search(query).stream()
			.map(place -> new PlaceSearchResponseDto(
				place.placeName(),
				place.roadAddressName().isEmpty() ? place.addressName() : place.roadAddressName(),	// §D2 주소 규칙
				place.lat(),
				place.lng(),
				GridEncoder.encode(place.lat(), place.lng())))	// 즉석 계산 — 저장 아님
			.toList();
	}
}

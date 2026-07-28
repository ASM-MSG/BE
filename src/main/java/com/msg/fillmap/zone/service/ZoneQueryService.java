package com.msg.fillmap.zone.service;

import java.util.List;

import com.msg.fillmap.zone.dto.SearchResultResponseDto;
import com.msg.fillmap.zone.dto.ZoneResponseDto;

/**
 * 구역 조회·장소 검색 계약 (MSG-234, Owner A 내부). 목록(getZones)과 검색(search)을 겸한다 —
 * 둘 다 zone 주도 조회라 한 서비스로 묶는다(§Owner). 크로스오너 경계면 아님.
 */
public interface ZoneQueryService {

	/** 전체 구역 목록(30~50행). 시딩 전이면 빈 리스트. */
	List<ZoneResponseDto> getZones();

	/**
	 * 장소 검색 (§D6). zones.name 매치가 있으면 zone 결과만, 없으면 regions.region_name 폴백.
	 * q 는 trim 후 빈 문자열이면 조회 없이 빈 리스트(ILIKE '%%' 전건 매치 차단), limit 은 1~50 clamp 후
	 * 두 분기 모두 적용한다.
	 */
	List<SearchResultResponseDto> search(String q, int limit);
}

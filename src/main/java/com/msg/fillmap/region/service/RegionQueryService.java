package com.msg.fillmap.region.service;

import java.util.Optional;

/**
 * 역지오코딩 조회 계약 (MSG-93, Owner A 제공). RegionController 와 미래 MSG-66 업로드 라벨러(Owner B)가 공유한다 —
 * 한 쿼리, 두 소비처. 반환은 내부 뷰(RegionView), HTTP 응답 DTO 변환은 컨트롤러 책임.
 */
public interface RegionQueryService {

	/**
	 * 좌표(lat, lon)를 포함하는 행정동 1건. 포함 행정동이 없으면(바다·국외) Optional.empty (예외 아님 — §D3).
	 * lat/lon 이 유효범위·서비스범위(한국) 밖이면 RegionErrorCode.INVALID_COORDINATE(6400).
	 */
	Optional<RegionView> resolveByPoint(double lat, double lon);
}

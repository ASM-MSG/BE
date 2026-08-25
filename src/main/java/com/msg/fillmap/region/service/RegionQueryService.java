package com.msg.fillmap.region.service;

import java.util.List;
import java.util.Optional;

import com.msg.fillmap.grid.dto.ViewportBounds;

/**
 * 역지오코딩 조회 계약 (MSG-93, Owner A 제공). RegionController 와 미래 MSG-66 업로드 라벨러(Owner B)가 공유한다 —
 * 한 쿼리, 두 소비처. 반환은 내부 뷰(RegionView), HTTP 응답 DTO 변환은 컨트롤러 책임.
 * 언급 지명 대조(matchMentionedRegions)는 MSG-468 — route(Owner B) 화면 밖 지역 신호의 행정구역 출처.
 */
public interface RegionQueryService {

	/**
	 * 좌표(lat, lon)를 포함하는 행정동 1건. 포함 행정동이 없으면(바다·국외) Optional.empty (예외 아님 — §D3).
	 * lat/lon 이 유효범위·서비스범위(한국) 밖이면 RegionErrorCode.INVALID_COORDINATE(6400).
	 */
	Optional<RegionView> resolveByPoint(double lat, double lon);

	/** 언급 지명 대조: 시도(접미 보정), 시군구, 동 세 단위 완전 일치 그룹. 매칭 없으면 빈 리스트. */
	List<MentionedRegionMatch> matchMentionedRegions(String name, ViewportBounds viewport);

	record MentionedRegionMatch(String name, double centerLat, double centerLng,
		double minLat, double minLng, double maxLat, double maxLng, boolean overlapsViewport) {
	}
}

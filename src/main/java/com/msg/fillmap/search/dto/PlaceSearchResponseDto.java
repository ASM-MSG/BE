package com.msg.fillmap.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 장소 검색 결과 1건 (MSG-251). gridId 는 응답 조립 시 GridEncoder 로 즉석 계산한 값 — 저장·캐시가 아니다
 * (약관 하드 제약, §D6). address 는 도로명 우선 1필드(§D2) — FE 분기 제거용. regionName(행정동 라벨)은
 * 표시 정보 중복 + 결과당 geospatial 1회 비용으로 기각(§D2), 필요 시 필드 추가만으로 확장한다(Open Q1).
 */
@Schema(description = "장소 검색 결과 1건. 선택 시 lat/lng 로 지도 이동 + gridId 로 격자 하이라이트를 한 번에 처리한다.")
public record PlaceSearchResponseDto(
	@Schema(description = "장소명 (카카오 place_name)", example = "부산대학교")
	String name,

	@Schema(description = "표시용 주소 — 도로명 우선, 없으면 지번 (§D2)", example = "부산 금정구 부산대학로63번길 2")
	String address,

	@Schema(description = "위도 (WGS84, 카카오 y 직결 — 변환 없음)", example = "35.23272")
	double lat,

	@Schema(description = "경도 (WGS84, 카카오 x)", example = "129.08246")
	double lng,

	@Schema(description = "그 좌표의 격자 ID — FE 격자 하이라이트 키 (즉석 계산, 저장 아님)", example = "39147_112245")
	String gridId
) {
}

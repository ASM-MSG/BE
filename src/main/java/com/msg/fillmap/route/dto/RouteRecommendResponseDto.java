package com.msg.fillmap.route.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI 경로 추천 응답 (MSG-457 §API). 성공은 항상 200 — 후보 부족(0~2개)도 notice 를 실은 성공이다
 * (FR-ROUTE-07). 시각 필드가 없어 시각 컨벤션(MSG-376) 검사 대상이 아니다 — 기간 정보는 reason 문장에
 * 녹아 나간다.
 */
@Schema(description = "AI 경로 추천 응답", requiredProperties = {"points", "notice", "mentionedArea"})
public record RouteRecommendResponseDto(

	@Schema(description = "방문 순서대로 정렬된 지점 목록 (최대 8개)")
	List<RoutePointDto> points,

	@Schema(description = "후보 부족 안내 — 지점 3개 이상이면 null, 0~2개면 안내 문구", nullable = true)
	String notice,

	@Schema(description = "언급 지역 신호 (MSG-468) — 문장이 화면 밖 지역을 말했으면 이동·축소 제안 재료가 실린다. "
		+ "무신호(지역 무언급·동명 다수·대조 실패·충분히 담김)가 기본값", nullable = true)
	MentionedAreaDto mentionedArea
) {

	/** 추천 지점 하나 — 좌표·격자·표시명 재료만으로 FE 가 지도에 점과 선을 그린다 (FR-ROUTE-02). */
	@Schema(description = "추천 지점",
		requiredProperties = {"order", "name", "kind", "lat", "lng", "gridId", "zoneName", "zoneCell",
			"regionName", "reason", "missionId", "occurrenceId"})
	public record RoutePointDto(

		@Schema(description = "방문 순서 (1부터 연속)", example = "1")
		int order,

		@Schema(description = "지점 이름 (원문 그대로 — AI 로 보낼 때만 100자 절단)", example = "해운대 빛축제")
		String name,

		@Schema(description = "지점 종류 — MISSION_FESTIVAL·MISSION_POPUP·MISSION_COURSE·EVENT·PLACE. FE 마커 분기용",
			example = "MISSION_FESTIVAL")
		String kind,

		@Schema(description = "대표 좌표 위도 (WGS84)", example = "35.1587")
		double lat,

		@Schema(description = "대표 좌표 경도 (WGS84)", example = "129.1604")
		double lng,

		@Schema(description = "격자 ID — 대표 좌표를 GridEncoder 로 즉석 계산", example = "16941_11439")
		String gridId,

		@Schema(description = "표시명 구역 이름 (MSG-341). 구역 밖이면 zoneCell 과 쌍으로 null", nullable = true)
		String zoneName,

		@Schema(description = "표시명 구역 셀", example = "B-3", nullable = true)
		String zoneCell,

		@Schema(description = "행정동 폴백 재료 (MSG-349 정책 동일). 무귀속이면 null", nullable = true)
		String regionName,

		@Schema(description = "추천 이유 한 줄 — AI explain 응답의 reasons 항목 그대로 (FR-ROUTE-05)")
		String reason,

		@Schema(description = "미션 후보면 미션 id — FE 가 미션 상세로 잇는 데 쓴다", nullable = true)
		Long missionId,

		@Schema(description = "행사 후보면 회차 id", nullable = true)
		Long occurrenceId
	) {
	}

	/**
	 * 언급 지역 신호 (MSG-468 §API). 이름·좌표는 서버 데이터(regions·zones)에서만 나온다 — AI 반환 문자열이
	 * 그대로 실리지 않는다(데이터 정합). 외접 사각형은 kind 와 무관하게 항상 실린다 — FE 가 이동·축소의
	 * 축척을 정하는 재료다(FR-4). 추천 결과(points·notice)에는 아무 영향이 없다(FR-5).
	 */
	@Schema(description = "언급 지역 신호 — 지도 이동(MOVE)·축소(ZOOM_OUT) 제안의 이름·중심·범위 재료",
		requiredProperties = {"name", "centerLat", "centerLng", "minLat", "minLng", "maxLat", "maxLng", "kind"})
	public record MentionedAreaDto(

		@Schema(description = "지역의 정식 표기 — 행정구역 매칭 단위 토큰 또는 구역 통칭(zones.name)",
			example = "부산광역시")
		String name,

		@Schema(description = "지역 중심 위도 (WGS84) — 행정구역은 경계 무게중심, 구역은 외접 사각형 중점",
			example = "35.1985")
		double centerLat,

		@Schema(description = "지역 중심 경도 (WGS84)", example = "129.0538")
		double centerLng,

		@Schema(description = "외접 사각형 남단 위도 (WGS84)", example = "35.0512")
		double minLat,

		@Schema(description = "외접 사각형 서단 경도 (WGS84)", example = "128.7602")
		double minLng,

		@Schema(description = "외접 사각형 북단 위도 (WGS84)", example = "35.3891")
		double maxLat,

		@Schema(description = "외접 사각형 동단 경도 (WGS84)", example = "129.2723")
		double maxLng,

		@Schema(description = "신호 종류 — MOVE(뷰포트와 안 겹침, 이동 제안)·ZOOM_OUT(겹치지만 뚜렷이 좁음, 축소 제안)",
			example = "MOVE")
		String kind
	) {
	}
}

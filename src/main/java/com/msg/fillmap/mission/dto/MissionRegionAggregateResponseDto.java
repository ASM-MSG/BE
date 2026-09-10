package com.msg.fillmap.mission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 넓은 축척 미션 행정 단위 집계 항목 (MSG-437). 뷰포트 안 미션을 행정동 코드 접두로 묶어 지역 이름과
 * 개수로 내려준다 — 축소 화면에서 핀 대신 그릴 묶음 마커 하나가 이 항목 하나다.
 *
 * 격자 집계(RegionAggregateResponseDto, MSG-356)를 재사용하지 않는 이유: count 의 뜻이 다르고
 * (점령 격자 수 대 미션 수) missionIds 가 미션 전용이라, Owner A DTO 에 미션 사정을 심는 것보다
 * mission 패키지 신설이 경계에 맞다(D4).
 */
@Schema(description = "행정 단위로 묶어 센 미션 집계 한 항목",
	requiredProperties = {"regionCode", "name", "lat", "lng", "count", "missionIds"})
public record MissionRegionAggregateResponseDto(

	@Schema(description = "묶음 키 — 행정동 코드(10자리)를 단위 길이로 자른 접두(동 10, 구 5, 시 2자리). "
		+ "행정동이 판정되지 않은 묶음만 null", example = "26230", nullable = true)
	String regionCode,

	@Schema(description = "단위 표시 이름 (동 \"부전2동\", 구 \"부산진구\", 시 \"부산광역시\"). 무귀속만 null",
		example = "부산진구", nullable = true)
	String name,

	@Schema(description = "마커 대표 좌표 위도 — 묶음에 속한 미션 귀속점의 평균이라 마커가 실제 데이터 위에 선다",
		example = "35.1568")
	double lat,

	@Schema(description = "마커 대표 좌표 경도", example = "129.0592")
	double lng,

	@Schema(description = "그 단위 안의 미션 수", example = "12")
	int count,

	@Schema(description = "그 묶음에 속한 미션 id 오름차순 — 줌인 후 개별 조회 결과와 교집합으로 목록을 좁힌다(D5). "
		+ "크기는 count 와 같다")
	List<Long> missionIds
) {
}

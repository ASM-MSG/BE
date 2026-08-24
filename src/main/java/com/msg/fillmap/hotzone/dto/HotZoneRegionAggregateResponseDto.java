package com.msg.fillmap.hotzone.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 넓은 축척 핫구역 행정 단위 집계 항목 (MSG-466). 뷰포트 안 핫 격자를 행정동 코드 접두로 묶어 지역 이름과
 * 개수로 내려준다 — 축소 화면에서 낱개 격자 마커 대신 그릴 묶음 마커 하나가 이 항목 하나다.
 *
 * 격자 집계(RegionAggregateResponseDto, MSG-356)를 재사용하지 않는 이유는 미션 집계(MSG-437 D4)와 같다:
 * count 의 뜻이 다르고(점령 격자 수 대 핫 격자 수) gridIds 가 핫구역 전용이라 hotzone 패키지 신설이 경계에 맞다.
 */
@Schema(description = "행정 단위로 묶어 센 핫구역 집계 한 항목",
	requiredProperties = {"regionCode", "name", "lat", "lng", "count", "gridIds"})
public record HotZoneRegionAggregateResponseDto(

	@Schema(description = "묶음 키 — 행정동 코드(10자리)를 단위 길이로 자른 접두(동 10, 구 5, 시 2자리). "
		+ "행정동이 판정되지 않은 묶음만 null", example = "26230", nullable = true)
	String regionCode,

	@Schema(description = "단위 표시 이름 (동 \"부전2동\", 구 \"부산진구\", 시 \"부산광역시\"). 무귀속만 null",
		example = "부산진구", nullable = true)
	String name,

	@Schema(description = "마커 대표 좌표 위도 — 묶음에 속한 핫 격자 셀 중심의 평균이라 마커가 실제 데이터 위에 선다",
		example = "35.1568")
	double lat,

	@Schema(description = "마커 대표 좌표 경도", example = "129.0592")
	double lng,

	@Schema(description = "그 단위 안의 핫 격자 수 — 핫스코어 합산이 아니다", example = "12")
	int count,

	@Schema(description = "그 묶음에 속한 핫 격자 id 오름차순 — 줌인 후 개별 조회 결과와 교집합으로 목록을 좁힌다(D4). "
		+ "크기는 count 와 같다")
	List<String> gridIds
) {
}

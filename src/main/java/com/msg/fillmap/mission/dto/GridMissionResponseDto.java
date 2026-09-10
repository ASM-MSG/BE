package com.msg.fillmap.mission.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 격자로 되짚은 미션 (MSG-459 §API 2). 지도에서 누른 격자를 대표 격자로 갖는 축제·팝업 한 건이다.
 * <p>
 * 진행 중인지 시작 전인지 끝났는지는 startAt·endAt 을 서버 시각과 견주어 화면이 판정한다 — 상태
 * 문자열을 따로 두면 같은 사실을 두 벌로 표현하게 되기 때문이다(D-4). 대신 배열 첫 항목이 화면 진입
 * 기본값이 되도록 정렬만 서버가 계약으로 잡는다.
 * <p>
 * 표시명 재료(zoneName·zoneCell·regionName)는 담지 않는다 — 이 조회는 정의상 요청 격자가 곧 대표
 * 격자라, 격자 상세 화면이 이미 가진 이름을 되돌려주는 셈이 된다(행사 역조회와 다른 점).
 */
@Schema(description = "격자가 대표 격자인 미션",
	requiredProperties = {"missionId", "type", "title", "startAt", "endAt", "videoCount"})
public record GridMissionResponseDto(
	@Schema(description = "미션 ID — 상세(GET /api/missions/{missionId})로 넘어가는 키", example = "412")
	Long missionId,

	@Schema(description = "미션 종류 — EVENT(지역축제) 또는 POPUP(팝업스토어)", example = "EVENT")
	String type,

	@Schema(description = "미션 이름", example = "부산 불꽃축제")
	String title,

	// 시더가 적재하는 축제·팝업은 기간을 항상 채우지만, 손으로 넣은 미션은 비어 있을 수 있어 nullable 이다.
	@Schema(description = "시작 시각", example = "2026-10-01T00:00:00Z", nullable = true)
	LocalDateTime startAt,

	@Schema(description = "종료 시각", example = "2026-10-07T14:59:59Z", nullable = true)
	LocalDateTime endAt,

	@Schema(description = "미션 기간 안에 촬영된 전역 공개 영상 수 — 미션 상세의 videoCount 와 같은 술어다",
		example = "37")
	long videoCount
) {
}

package com.msg.fillmap.video.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.mission.dto.CompletedMissionResponseDto;

/**
 * 메타데이터 저장 응답. occupied = 이 업로드로 격자를 처음 점령했는지(첫 방문) 여부.
 * newBadges = 이 업로드로 새로 획득한 뱃지(MSG-239 FR-9) — 없으면 빈 배열.
 * completedMissions = 이 업로드로 완료된 미션 스탬프(MSG-223 FR-19) — 없으면 빈 배열.
 * zoneName·zoneCell·regionName = 업로드 직후 화면이 "어디를 채웠는지" 이름으로 보여주기 위한 격자 표시명
 * 재료다(MSG-341). 조립은 클라이언트 몫으로 `zoneName 있으면 "{zoneName} {zoneCell}", 없으면 regionName`
 * 한 줄이며, 서버는 폴백 문자열을 만들지 않는다.
 */
@Schema(description = "영상 메타데이터 저장 응답",
	requiredProperties = {"videoId", "gridId", "processingStatus", "occupied", "newBadges", "completedMissions",
		"zoneName", "zoneCell", "regionName"})
public record VideoUploadResponseDto(
	@Schema(description = "생성된 영상 ID", example = "1001")
	Long videoId,

	@Schema(description = "매핑된 격자 ID", example = "19422_9582")
	String gridId,

	@Schema(description = "영상 처리 상태 (UPLOADED/ENCODING/BLURRING/READY/FAILED)", example = "UPLOADED")
	String processingStatus,

	@Schema(description = "이 업로드로 격자를 처음 점령(첫 방문)했는지 여부", example = "true")
	boolean occupied,

	@Schema(description = "이 업로드로 새로 획득한 뱃지 목록 — 없으면 빈 배열")
	List<EarnedBadgeResponseDto> newBadges,

	@Schema(description = "이 업로드로 완료된 미션 스탬프 목록 — 없으면 빈 배열")
	List<CompletedMissionResponseDto> completedMissions,

	@Schema(description = "격자가 속한 구역 이름 (예 \"서면\"). 구역 밖 격자면 null — 이때 라벨은 regionName 이다",
		example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 내 위치 코드 \"{행}-{열}\" (행 A 는 구역 북단, 열 1 은 서단). zoneName 과 항상 "
		+ "쌍이라 구역 밖이면 함께 null", example = "I-6", nullable = true)
	String zoneCell,

	@Schema(description = "격자 중심점 행정동 이름 — 구역 밖 격자의 폴백 라벨. 무귀속(해상 등)이거나 "
		+ "미판정이면 null", example = "서울특별시 강남구 역삼1동", nullable = true)
	String regionName
) {
}

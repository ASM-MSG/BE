package com.msg.fillmap.event.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 행사 위치 하나 (MSG-439 API 3). gridIds 는 FE 영역 채색 재료고, 영상은 그중 representativeGridId
 * 하나에만 붙는다 (FR-EVENT-08). 표시명 재료 세 필드는 대표 격자 기준이며 조립 규칙은 격자 응답 9종과 같다:
 * {@code zoneName ? zoneName + " " + zoneCell : regionName} (MSG-341·349).
 * <p>
 * 참여 속성 5종(MSG-500 D-8)은 <b>이벤트 참여형 승인분에만</b> 값이 있다 — 시드 위치는 전부 null 이라
 * 기존 화면은 필드가 는 것 외에 달라지지 않는다. {@code participationStartsOn}·{@code participationEndsOn}
 * 은 표기 정보이지 노출 창이 아니다(위치 노출은 부모 회차 생명주기와 중지 여부가 지배한다).
 */
@Schema(description = "행사 위치 하나 — 영역 격자·대표 격자·표시명 재료·영상 수",
	requiredProperties = {"locationId", "name", "type", "operatingHours", "gridIds", "representativeGridId",
		"zoneName", "zoneCell", "regionName", "videoCount", "organizerName", "description",
		"participationStartsOn", "participationEndsOn", "participationMethod", "imageUrl"})
public record EventLocationResponseDto(
	@Schema(description = "행사 위치 id — 위치별 영상 피드 진입 키", example = "31")
	Long locationId,

	@Schema(description = "위치 이름", example = "부산역 팝업")
	String name,

	@Schema(description = "위치 유형 — 표시 라벨 변환은 FE 몫", example = "POPUP",
		allowableValues = {"POPUP", "EXPERIENCE_ZONE", "PARADE", "PHOTO_ZONE", "ETC"})
	String type,

	@Schema(description = "운영 시간 표시 문자열", example = "11:00 ~ 20:00", nullable = true)
	String operatingHours,

	@Schema(description = "영역을 구성하는 격자 전체 — FE 영역 채색 재료", example = "[\"19443_9582\"]")
	List<String> gridIds,

	@Schema(description = "대표 격자 — 이 위치의 영상이 붙는 단 하나의 격자", example = "19443_9582")
	String representativeGridId,

	@Schema(description = "대표 격자가 속한 구역 이름. 구역 밖이면 null", example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 안 위치 코드. 구역 밖이면 null", example = "A-14", nullable = true)
	String zoneCell,

	@Schema(description = "대표 격자의 행정동 이름 — 구역 밖 표시명 폴백. 무귀속이면 null",
		example = "부전동", nullable = true)
	String regionName,

	@Schema(description = "이 위치의 영상 수 — 조회 시점 실측(전역 노출 게이트 통과분)", example = "7")
	Long videoCount,

	@Schema(description = "운영 주체 — 참여형 승인분만 값이 있다", example = "필맵 주식회사", nullable = true)
	String organizerName,

	@Schema(description = "참여 소개 — 참여형 승인분만 값이 있다", nullable = true)
	String description,

	@Schema(description = "공개 시작일 (표기 정보, 노출 창 아님)", example = "2026-11-07", nullable = true)
	LocalDate participationStartsOn,

	@Schema(description = "공개 종료일 (표기 정보, 노출 창 아님)", example = "2026-11-09", nullable = true)
	LocalDate participationEndsOn,

	@Schema(description = "참여 방식 서술 — 참여형 승인분만 값이 있다", nullable = true)
	String participationMethod,

	@Schema(description = "커버 이미지 공개 URL — 참여형 승인분만 값이 있다", nullable = true)
	String imageUrl
) {
}

package com.msg.fillmap.mission.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.mission.entity.Mission;

/**
 * 활성 미션 하나 (MSG-222 §API 명세). 공통 필드 + 유형별 shape 하나. FE 는 리스트를 순회하며 type 으로 렌더러를
 * 스위치한다(면/폴리라인/점/경계). per-user 필드 없음 — 모든 사용자에게 동일해 전역 캐시가 성립한다(§개요).
 *
 * MSG-383 이 화면용 메타데이터 8종을 더한다(additive — 기존 필드의 이름·타입·의미 불변). 값이 없어도 키는
 * 나가므로 required 와 nullable 을 함께 단다. MSG-383 §API 명세는 "전부 nullable 이라 requiredProperties 에
 * 넣지 않는다"고 적었지만, 이 레포에서 required 는 "값이 non-null"이 아니라 "키가 존재한다"는 뜻이고
 * (Jackson 기본 직렬화) 그 계약을 ResponseSchemaNullabilityTest 가 강제한다(MSG-319). 스펙이 노린 "필드를
 * 빼지 않고 null 로 내려 FE 분기를 하나로 유지한다"가 곧 required + nullable 이다.
 *
 * 목록·상세로 나누지 않은 이유는 상세 전용 DTO 가 아직 없어서다 — 지금 나누면 상세 티켓이 들어올 때
 * 소비처가 두 번 바뀐다(§D8).
 */
@Schema(description = "활성 미션 하나 — 공통 필드 + 유형별 렌더 shape",
	requiredProperties = {"missionId", "type", "title", "targetCount", "shape", "startAt", "endAt",
		"description", "placeName", "sourceUrl", "operationTime", "imageUrl",
		"distanceMeters", "durationMinutes", "difficulty"})
public record MissionResponseDto(
	@Schema(description = "미션 id (missions.id)", example = "12")
	Long missionId,

	@Schema(description = "미션 유형 — FE 렌더러 판별자",
		example = "COURSE", allowableValues = {"COURSE", "AREA", "EVENT", "THEME", "CONTINUOUS", "POPUP"})
	String type,

	@Schema(description = "미션 제목", example = "남파랑길 3코스")
	String title,

	@Schema(description = "완료에 필요한 distinct 방문 격자 수(표시·판정 힌트, 판정은 MSG-223)", example = "3")
	Integer targetCount,

	@Schema(description = "시작 시각. NULL = 무기간(상시)", example = "2026-11-01T00:00:00Z", nullable = true)
	LocalDateTime startAt,

	@Schema(description = "종료 시각. NULL = 무기간(상시)", example = "2026-11-01T23:59:59Z", nullable = true)
	LocalDateTime endAt,

	@Schema(description = "유형별 렌더 shape 하나(type 에 대응하는 PATH/BOX/CELLS/REGION)")
	MissionShape shape,

	@Schema(description = "소개문 원문. 출처 표기 없이 그대로 노출한다", nullable = true,
		example = "부산 앞바다를 따라 걷는 해안 산책로")
	String description,

	@Schema(description = "사람이 읽는 위치 한 줄 — 축제는 행사장, 팝업은 주소, 코스는 시군",
		nullable = true, example = "부산 영도구")
	String placeName,

	@Schema(description = "원문 링크 — 축제 홈페이지·팝업 상세 페이지. 코스는 없다",
		nullable = true, example = "https://festival.example.kr")
	String sourceUrl,

	@Schema(description = "운영시간 안내 문구. 여러 줄이면 개행으로 이어 붙인다(팝업 전용)",
		nullable = true, example = "매일 11:00 ~ 20:00")
	String operationTime,

	@Schema(description = "대표 이미지 주소 — 우리 스토리지 URL 만 들어간다(MSG-383 §D7)",
		nullable = true, example = "https://cdn.fillmap.kr/mission/12.webp")
	String imageUrl,

	@Schema(description = "코스 총 거리(미터). 코스가 아니면 없다", nullable = true, example = "14000")
	Integer distanceMeters,

	@Schema(description = "코스 소요시간(분). 코스가 아니면 없다", nullable = true, example = "330")
	Integer durationMinutes,

	@Schema(description = "코스 난이도 — 두루누비 등급 1(쉬움)·2(보통)·3(어려움). 코스가 아니면 없다",
		nullable = true, example = "2")
	Integer difficulty
) {

	/**
	 * shape 는 서비스가 유형별로 합성해 넘긴다(§도메인 3). 메타데이터 8종(MSG-383)은 엔티티에서 그대로
	 * 읽는다 — 값이 없으면 필드를 빼지 않고 null 로 내려 FE 분기를 하나로 유지한다(§API 명세).
	 */
	public static MissionResponseDto of(Mission mission, MissionShape shape) {
		return new MissionResponseDto(
			mission.getId(),
			mission.getType().name(),
			mission.getTitle(),
			mission.getTargetCount(),
			mission.getStartAt(),
			mission.getEndAt(),
			shape,
			mission.getDescription(),
			mission.getPlaceName(),
			mission.getSourceUrl(),
			mission.getOperationTime(),
			mission.getImageUrl(),
			mission.getDistanceMeters(),
			mission.getDurationMinutes(),
			mission.getDifficulty());
	}
}

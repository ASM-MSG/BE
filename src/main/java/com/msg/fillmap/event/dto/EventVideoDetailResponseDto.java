package com.msg.fillmap.event.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 행사 영상 상세 (MSG-440 API 3). 재생 화면 한 장의 재료다 — 재생본 URL 과 영상 메타에 더해, 이 영상이
 * 어느 행사·어느 위치·어느 대표 격자의 것인지를 함께 준다(US-006 "대표 격자 표시").
 * 표시명 조립은 클라이언트 몫으로 {@code zoneName 있으면 "{zoneName} {zoneCell}", 없으면 regionName} 이며
 * 서버는 폴백 문자열을 만들지 않는다(MSG-341 공통 규칙).
 * 도움돼요 수·댓글 수·댓글 첫 페이지도 함께 온다(MSG-441) — 화면이 상세 진입에 목록 API 를 한 번 더
 * 부르지 않게 첫 20건을 품고, 둘째 페이지부터 GET /api/event-videos/{videoId}/comments 를 쓴다.
 */
@Schema(description = "행사 영상 상세",
	requiredProperties = {"videoId", "occurrenceId", "occurrenceStatus", "locationId", "locationName",
		"representativeGridId", "zoneName", "zoneCell", "regionName", "playbackUrl", "durationSec",
		"recordedAt", "createdAt", "uploaderNickname", "interactionLocked", "helpfulCount", "helpfulByMe",
		"commentCount", "comments"})
public record EventVideoDetailResponseDto(
	@Schema(description = "영상 ID", example = "1042")
	Long videoId,

	@Schema(description = "소속 행사 회차 ID", example = "12")
	Long occurrenceId,

	@Schema(description = "요청 시점 회차 상태 (UPCOMING/LIVE/UPLOAD_GRACE/ARCHIVED)", example = "LIVE")
	String occurrenceStatus,

	@Schema(description = "소속 행사 위치 ID", example = "34")
	Long locationId,

	@Schema(description = "소속 행사 위치 이름", example = "영화의전당")
	String locationName,

	@Schema(description = "영상이 붙은 대표 격자 ID", example = "19422_9582")
	String representativeGridId,

	@Schema(description = "대표 격자가 속한 구역 이름. 구역 밖이면 null — 이때 라벨은 regionName 이다",
		example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 내 위치 코드 \"{행}-{열}\". zoneName 과 항상 쌍이라 구역 밖이면 함께 null",
		example = "A-14", nullable = true)
	String zoneCell,

	@Schema(description = "대표 격자 중심점 행정동 이름 — 구역 밖 격자의 폴백 라벨. 무귀속이면 null",
		example = "부산광역시 부산진구 부전2동", nullable = true)
	String regionName,

	@Schema(description = "재생본 presigned GET URL")
	String playbackUrl,

	@Schema(description = "영상 길이(초)", example = "15")
	Short durationSec,

	@Schema(description = "촬영 시각", example = "2026-10-06T12:00:00Z")
	LocalDateTime recordedAt,

	@Schema(description = "업로드 시각", example = "2026-10-06T12:30:00Z")
	LocalDateTime createdAt,

	@Schema(description = "작성자 닉네임", example = "필맵러")
	String uploaderNickname,

	@Schema(description = "댓글·도움돼요 입력 UI 를 비활성화할지 여부 — 아카이브 전환(행사 종료 + 30일)부터 true",
		example = "false")
	boolean interactionLocked,

	@Schema(description = "도움돼요 수", example = "12")
	long helpfulCount,

	@Schema(description = "내가 도움돼요를 누른 상태인지. 비로그인 조회는 항상 false", example = "false")
	boolean helpfulByMe,

	@Schema(description = "댓글 수", example = "3")
	long commentCount,

	@Schema(description = "댓글 첫 페이지 (오래된 순 20건)")
	EventVideoCommentPageResponseDto comments
) {
}

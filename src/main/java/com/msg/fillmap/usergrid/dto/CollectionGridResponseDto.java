package com.msg.fillmap.usergrid.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.usergrid.service.CollectionGridView;

/**
 * 갤러리 격자 목록 항목 응답 (MSG-153). 개인 도감 갤러리 뷰의 셀 하나 — 최근 수집순 최대 30개로 내려간다.
 * coverVideoId 는 user_grids.cover_video_id 그대로(cover 없으면 null, readiness 무관 — 스펙 §API),
 * coverThumbnailUrl 은 cover 가 없거나 READY 이전·썸네일 미발급이면 null(§D4). 인코딩 중 cover 는
 * id 만 있고 url 이 null 인 게 정상 상태다 — FE 는 url null 을 플레이스홀더로 처리한다.
 */
@Schema(description = "갤러리 격자 항목 — 내가 수집한 격자 하나와 cover 썸네일.")
public record CollectionGridResponseDto(
	@Schema(description = "격자 ID \"{grid_y}_{grid_x}\"", example = "41642_110458")
	String gridId,

	@Schema(description = "격자 Y 인덱스(지도 이동용, gridId 디코드값)", example = "41642")
	Integer gridY,

	@Schema(description = "격자 X 인덱스(지도 이동용, gridId 디코드값)", example = "110458")
	Integer gridX,

	@Schema(description = "최초 수집(점령) 시각 — 정렬 키", example = "2026-07-20T18:03:11")
	LocalDateTime firstCollectedAt,

	@Schema(description = "마지막 방문(업로드) 시각", example = "2026-07-21T09:12:00")
	LocalDateTime lastUploadedAt,

	@Schema(description = "그 격자 내 내 영상 수", example = "3")
	Integer videoCount,

	@Schema(description = "cover 영상 ID(없으면 null)", example = "1042", nullable = true)
	Long coverVideoId,

	@Schema(description = "cover 썸네일 presigned GET URL(없거나 READY 이전이면 null)", nullable = true)
	String coverThumbnailUrl,

	@Schema(description = "격자 중심점 행정동 이름(무귀속/미판정이면 null)",
		example = "서울특별시 강남구 역삼1동", nullable = true)
	String regionName
) {

	public static CollectionGridResponseDto from(CollectionGridView view) {
		return new CollectionGridResponseDto(
			view.gridId(),
			view.gridY(),
			view.gridX(),
			view.firstCollectedAt(),
			view.lastUploadedAt(),
			view.videoCount(),
			view.coverVideoId(),
			view.coverThumbnailUrl(),
			view.regionName()
		);
	}
}

package com.msg.fillmap.friend.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.usergrid.service.FriendCollectionGridView;

/**
 * 친구 도감의 최근 수집 격자 항목 (MSG-186 FR-6). 본인 갤러리 항목(CollectionGridResponseDto)과 같은 형상이되
 * 영상 ID 는 담지 않는다 — 썸네일은 그 친구가 재생할 수 있는 영상 중 1건이라 "cover"라는 이름이 맞지 않고,
 * 비공개 영상의 id 노출 자체가 존재 누설이다 (§D6). videoCount 는 도감 요약의 totalVideoCount 와 같은 축
 * (비공개 포함 전체)이라 그대로 내려간다 — 개수는 요약이 이미 공개하는 정보다.
 */
@Schema(description = "친구가 수집한 격자 하나 — 썸네일은 재생 허용 영상이 있을 때만 붙는다.",
	requiredProperties = {"gridId", "gridY", "gridX", "firstCollectedAt", "lastUploadedAt", "videoCount",
		"thumbnailUrl", "regionName"})
public record FriendCollectionGridResponseDto(
	@Schema(description = "격자 ID \"{grid_y}_{grid_x}\"", example = "41642_110458")
	String gridId,

	@Schema(description = "격자 Y 인덱스(지도 이동용, gridId 디코드값)", example = "41642")
	Integer gridY,

	@Schema(description = "격자 X 인덱스(지도 이동용, gridId 디코드값)", example = "110458")
	Integer gridX,

	@Schema(description = "친구가 이 격자를 처음 수집한 시각 — 정렬 키", example = "2026-07-20T18:03:11")
	LocalDateTime firstCollectedAt,

	@Schema(description = "친구의 마지막 업로드 시각", example = "2026-07-21T09:12:00")
	LocalDateTime lastUploadedAt,

	@Schema(description = "그 격자에 친구가 올린 영상 수", example = "3")
	Integer videoCount,

	@Schema(description = "썸네일 presigned GET URL — 재생 허용 영상이 없으면 null", nullable = true)
	String thumbnailUrl,

	@Schema(description = "격자 중심점 행정동 이름(무귀속/미판정이면 null)",
		example = "서울특별시 강남구 역삼1동", nullable = true)
	String regionName
) {

	public static FriendCollectionGridResponseDto from(FriendCollectionGridView view) {
		return new FriendCollectionGridResponseDto(
			view.gridId(),
			view.gridY(),
			view.gridX(),
			view.firstCollectedAt(),
			view.lastUploadedAt(),
			view.videoCount(),
			view.thumbnailUrl(),
			view.regionName()
		);
	}
}

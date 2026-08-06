package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.repository.ExploreGridProjection;

/**
 * 전역 탐색 격자 카드 (MSG-238). 그 행정동에서 전역 노출 게이트(ACTIVE·PUBLIC·READY)를 통과한 영상이
 * 1건 이상인 격자 하나다(§D1). 커버는 findGlobalCover(87)와 같은 3키 규칙의 영상이라 격자 상세와 썸네일이
 * 어긋나지 않는다(§D7). 격자명("서면 A-14")·zone 은 미포함(MSG-234 보류) — FE 가 래퍼의 regionName 폴백으로
 * 조합한다. coverVideoId 미포함 — 카드 탭은 격자 진입(MSG-237)이지 재생이 아니다(§D7).
 */
@Schema(description = "전역 탐색 격자 카드",
	requiredProperties = {"gridId", "gridY", "gridX", "videoCount", "coverDurationSec", "coverThumbnailUrl"})
public record ExploreGridResponseDto(
	@Schema(description = "격자 ID — 카드 탭 시 격자 전역 영상 목록(MSG-237) 진입 키", example = "38879_112390")
	String gridId,

	@Schema(description = "격자 위도 인덱스 (FE 지도 이동·라벨 조합)", example = "38879")
	Long gridY,

	@Schema(description = "격자 경도 인덱스", example = "112390")
	Long gridX,

	@Schema(description = "그 격자의 게이트 통과 영상 수 — \"N개 영상\"", example = "138")
	Integer videoCount,

	@Schema(description = "커버 썸네일 presigned GET URL. READY 게이트라 non-null 기대(null 이면 null 통과)",
		nullable = true)
	String coverThumbnailUrl,

	@Schema(description = "커버 영상 길이(초) — duration 뱃지", example = "12")
	Short coverDurationSec
) {

	/** 커버 썸네일 presigned GET URL 은 서비스가 발급해 넘긴다 (projection 엔 S3 key 만 있어서다). */
	public static ExploreGridResponseDto of(ExploreGridProjection projection, String coverThumbnailUrl) {
		return new ExploreGridResponseDto(
			projection.getGridId(),
			projection.getGridY(),
			projection.getGridX(),
			projection.getVideoCount(),
			coverThumbnailUrl,
			projection.getCoverDurationSec());
	}
}

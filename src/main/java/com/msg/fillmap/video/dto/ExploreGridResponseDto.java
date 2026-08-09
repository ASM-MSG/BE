package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.repository.ExploreGridProjection;

/**
 * 전역 탐색 격자 카드 (MSG-238). 그 행정동에서 전역 노출 게이트(ACTIVE·PUBLIC·READY)를 통과한 영상이
 * 1건 이상인 격자 하나다(§D1). 커버는 findGlobalCover(87)와 같은 3키 규칙의 영상이라 격자 상세와 썸네일이
 * 어긋나지 않는다(§D7). 격자명("서면 A-14")의 두 조각 zoneName·zoneCell 을 서버가 계산해 담는다
 * (MSG-341 — MSG-234 §D3 의 "표시명은 클라이언트 계산" 보류가 풀렸다). 구역 밖 격자면 둘 다 null 이고,
 * 그때 FE 는 래퍼 응답(RegionExploreResponseDto)의 regionName 으로 폴백한다 — 항목에 regionName 을 다시
 * 담지 않는 이유다. coverVideoId 미포함 — 카드 탭은 격자 진입(MSG-237)이지 재생이 아니다(§D7).
 */
@Schema(description = "전역 탐색 격자 카드",
	requiredProperties = {"gridId", "gridY", "gridX", "videoCount", "coverDurationSec", "coverThumbnailUrl",
		"zoneName", "zoneCell"})
public record ExploreGridResponseDto(
	@Schema(description = "격자 ID — 카드 탭 시 격자 전역 영상 목록(MSG-237) 진입 키", example = "16676_11596")
	String gridId,

	@Schema(description = "격자 세로 인덱스 (EPSG:5179 평면 y / 100 — 위도가 아니다). FE 지도 이동·라벨 조합", example = "16676")
	Long gridY,

	@Schema(description = "격자 가로 인덱스 (EPSG:5179 평면 x / 100 — 경도가 아니다)", example = "11596")
	Long gridX,

	@Schema(description = "그 격자의 게이트 통과 영상 수 — \"N개 영상\"", example = "138")
	Integer videoCount,

	@Schema(description = "커버 썸네일 presigned GET URL. READY 게이트라 non-null 기대(null 이면 null 통과)",
		nullable = true)
	String coverThumbnailUrl,

	@Schema(description = "커버 영상 길이(초) — duration 뱃지", example = "12")
	Short coverDurationSec,

	@Schema(description = "격자가 속한 구역 이름 (예 \"서면\"). 구역 밖 격자면 null — 이때 FE 는 래퍼의 "
		+ "regionName 을 라벨로 쓴다", example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 내 위치 코드 \"{행}-{열}\" (행 A 는 구역 북단, 열 1 은 서단). zoneName 과 항상 "
		+ "쌍이라 구역 밖이면 함께 null", example = "I-6", nullable = true)
	String zoneCell
) {

	/**
	 * 커버 썸네일 presigned GET URL 은 서비스가 발급해 넘긴다 (projection 엔 S3 key 만 있어서다).
	 * 구역 이름 2필드도 같은 결로 서비스가 계산해 넘긴다 — 요청당 리졸버 1회 규약(MSG-341 D-1)을
	 * 지키려면 계산 시점이 매핑 바깥이어야 해서, DTO 가 직접 zone 을 조회하지 않는다.
	 */
	public static ExploreGridResponseDto of(ExploreGridProjection projection, String coverThumbnailUrl,
		String zoneName, String zoneCell) {
		return new ExploreGridResponseDto(
			projection.getGridId(),
			projection.getGridY(),
			projection.getGridX(),
			projection.getVideoCount(),
			coverThumbnailUrl,
			projection.getCoverDurationSec(),
			zoneName,
			zoneCell);
	}
}

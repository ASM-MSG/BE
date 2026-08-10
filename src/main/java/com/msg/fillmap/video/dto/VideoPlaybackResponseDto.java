package com.msg.fillmap.video.dto;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.entity.Video;

/**
 * 단건 영상 재생 조회 응답 (MSG-206). 영상 하나의 표시용 메타 + 재생본 presigned GET URL 을 담는다.
 * playbackUrl·thumbnailUrl 은 S3 key 가 아니라 발급된 presigned GET URL 이며, 서비스가 접근 제어·처리상태에
 * 따라 발급 여부를 정한다(엔티티엔 S3 key 만 있어서다). status 는 소유자가 블라인드 사유(재생 불가)를
 * playbackUrl=null 만으로 구분 못 하는 걸 해소하는 축이다(§설계 M5).
 */
@Schema(description = "단건 영상 재생 조회 응답",
	requiredProperties = {"videoId", "gridId", "durationSec", "processingStatus", "visibility", "status",
		"viewCount", "recordedAt", "playbackUrl", "thumbnailUrl", "expiresInSec",
		"zoneName", "zoneCell", "regionName", "highlights"})
public record VideoPlaybackResponseDto(
	@Schema(description = "영상(방문 이벤트) ID", example = "1042")
	Long videoId,

	@Schema(description = "재생본 presigned GET URL. READY 아님·BLINDED(소유자)면 null", nullable = true)
	String playbackUrl,

	@Schema(description = "썸네일 presigned GET URL. 썸네일 key 없음(READY 이전)이면 null", nullable = true)
	String thumbnailUrl,

	@Schema(description = "이 영상이 속한 격자 ID", example = "19422_9582")
	String gridId,

	@Schema(description = "영상 길이(초, 최대 30)", example = "12")
	Short durationSec,

	@Schema(description = "영상 처리 상태 (UPLOADED/ENCODING/BLURRING/READY/FAILED)", example = "READY")
	String processingStatus,

	@Schema(description = "공개 범위 (PUBLIC, PRIVATE, FRIENDS 중 하나)", example = "PUBLIC")
	String visibility,

	@Schema(description = "영상 상태 (ACTIVE/BLINDED). 소유자가 블라인드 사유를 구분하는 축", example = "ACTIVE")
	String status,

	@Schema(description = "조회수 (이번 조회 증가 전 스냅샷)", example = "37")
	Long viewCount,

	@Schema(description = "촬영 시각 (표시용)", example = "2026-07-20T18:03:11")
	LocalDateTime recordedAt,

	@Schema(description = "playbackUrl presign TTL(초). playbackUrl=null 이면 null", nullable = true)
	Long expiresInSec,

	@Schema(description = "격자가 속한 구역 이름 (예 \"서면\"). 구역 밖 격자면 null — 이때 라벨은 regionName 이다",
		example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 내 위치 코드 \"{행}-{열}\" (행 A 는 구역 북단, 열 1 은 서단). zoneName 과 항상 "
		+ "쌍이라 구역 밖이면 함께 null", example = "I-6", nullable = true)
	String zoneCell,

	@Schema(description = "격자 중심점 행정동 이름 — 구역 밖 격자의 폴백 라벨. 무귀속(해상 등)이거나 "
		+ "미판정이면 null", example = "서울특별시 강남구 역삼1동", nullable = true)
	String regionName,

	@Schema(description = "AI 추천 하이라이트 구간 [[시작초, 끝초], ...]. 최대 3구간, 초는 소수점 둘째 자리. "
		+ "배열 순서가 추천 우선순위(첫 요소가 최우선 추천). 없으면 null (READY 이전·FAILED·0구간 포함, "
		+ "빈 배열은 내려가지 않는다) 예시: [[0.0, 4.25], [12.0, 18.5], [20.0, 27.5]]", nullable = true)
	List<List<Double>> highlights
) {

	/**
	 * 재생/썸네일 presigned GET URL 과 TTL 은 서비스가 발급해 넘긴다 (엔티티엔 S3 key 만 있어서다).
	 * 격자 표시명 3필드(MSG-341)도 같은 결이다 — 구역 이름은 요청당 1회 받은 리졸버(D-1)로, 행정동 이름은
	 * 격자 저장 라벨 조회(D-6)로 서비스가 구해 넘긴다. 엔티티엔 gridId 만 있어 DTO 혼자서는 못 만든다.
	 */
	public static VideoPlaybackResponseDto of(Video video, String playbackUrl, String thumbnailUrl, Long expiresInSec,
		String zoneName, String zoneCell, String regionName) {
		List<List<Double>> highlights = video.getHighlights();
		// 저장 혼재(null 또는 [])를 응답 null 하나로 정규화 (MSG-350 FR-2)
		if (highlights != null && highlights.isEmpty()) {
			highlights = null;
		}
		return new VideoPlaybackResponseDto(
			video.getId(),
			playbackUrl,
			thumbnailUrl,
			video.getGridId(),
			video.getDurationSec(),
			video.getProcessingStatus().name(),
			video.getVisibility().name(),
			video.getStatus().name(),
			video.getViewCount(),
			video.getRecordedAt(),
			expiresInSec,
			zoneName,
			zoneCell,
			regionName,
			highlights);
	}
}

package com.msg.fillmap.video.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.entity.Video;

/**
 * 격자 전역 영상 목록 항목 (MSG-237). 그 격자에 쌓인 공개·READY 영상 하나를 브라우징 피드 행으로 표현한다.
 * 대표 1건(GridCoverVideoResponseDto)·내 영상(GridVideoResponseDto)과 축이 달라 별도 DTO 로 둔다(§D3) —
 * 전역 목록은 항상 READY 라 processingStatus 가 무의미하고, 작성자는 닉네임만 담으며(전역 카드에 작성자를
 * 표시하기로 2026-08-04 확정 — MSG-371, 도감 색상은 여전히 비노출), title 은 videos.title 컬럼
 * 신설(MSG-240) 후 additive 로 붙는다.
 */
@Schema(description = "격자 전역 영상 목록 항목",
	requiredProperties = {"videoId", "thumbnailUrl", "durationSec", "viewCount", "recordedAt", "nickname"})
public record GridGlobalVideoResponseDto(
	@Schema(description = "영상 ID. 항목 탭 → 단건 재생(GET /api/videos/{videoId}) 진입 키", example = "1042")
	Long videoId,

	@Schema(description = "썸네일 presigned GET URL. 목록은 READY 만 담겨 null 아님이 기대값이다")
	String thumbnailUrl,

	@Schema(description = "영상 길이(초, 최대 30)", example = "12")
	Short durationSec,

	@Schema(description = "조회수 — 인기순 정렬 근거", example = "37")
	Long viewCount,

	@Schema(description = "촬영 시각 (표시용). 정렬 tie-break 키는 createdAt 이다", example = "2026-07-20T18:03:11")
	LocalDateTime recordedAt,

	@Schema(description = "작성자 닉네임 원문. @ 등 화면 표기는 FE 가 붙인다", example = "busan.vlog")
	String nickname
) {

	/**
	 * 썸네일 presigned GET URL 은 서비스가 발급해 넘긴다 (엔티티엔 S3 key 만 있어서다).
	 * 닉네임도 같은 결이다 — 엔티티엔 userId 만 있어 서비스가 페이지 단위 배치 조회로 구해 넘긴다(MSG-371).
	 */
	public static GridGlobalVideoResponseDto of(Video video, String thumbnailUrl, String nickname) {
		return new GridGlobalVideoResponseDto(
			video.getId(),
			thumbnailUrl,
			video.getDurationSec(),
			video.getViewCount(),
			video.getRecordedAt(),
			nickname);
	}
}

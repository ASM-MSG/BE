package com.msg.fillmap.video.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.entity.Video;

/**
 * 격자 전역 영상 목록 항목 (MSG-237). 그 격자에 쌓인 공개·READY 영상 하나를 브라우징 피드 행으로 표현한다.
 * 대표 1건(GridCoverVideoResponseDto)·내 영상(GridVideoResponseDto)과 축이 달라 별도 DTO 로 둔다(§D3) —
 * 전역 목록은 항상 READY 라 processingStatus 가 무의미하고, 작성자 식별 정보(닉네임·색)는 프라이버시상
 * 담지 않으며(glossary Phase 2+), title 은 videos.title 컬럼 신설(MSG-240) 후 additive 로 붙는다.
 */
@Schema(description = "격자 전역 영상 목록 항목")
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
	LocalDateTime recordedAt
) {

	/** 썸네일 presigned GET URL 은 서비스가 발급해 넘긴다 (엔티티엔 S3 key 만 있어서다). */
	public static GridGlobalVideoResponseDto of(Video video, String thumbnailUrl) {
		return new GridGlobalVideoResponseDto(
			video.getId(),
			thumbnailUrl,
			video.getDurationSec(),
			video.getViewCount(),
			video.getRecordedAt());
	}
}

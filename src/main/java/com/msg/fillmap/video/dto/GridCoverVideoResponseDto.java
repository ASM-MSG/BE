package com.msg.fillmap.video.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.entity.Video;

/**
 * 격자 전역 대표 영상 1건 (MSG-87). 그 격자를 전역에서 대표하는(조회수 → 최신) 공개·READY 영상 하나다.
 * MSG-127 의 "내 영상 리스트" 항목(GridVideoResponseDto)과 이름·의미가 충돌하지 않도록 별도 DTO 로 둔다.
 * 대표는 필터가 READY 를 강제하므로 thumbnailUrl 은 항상 발급되고 processingStatus 분기는 없다.
 * 작성자 식별 정보(닉네임·색)는 프라이버시상 담지 않는다(glossary Phase 2+).
 */
@Schema(description = "격자 전역 대표 영상",
	requiredProperties = {"videoId", "thumbnailUrl", "durationSec", "viewCount", "recordedAt"})
public record GridCoverVideoResponseDto(
	@Schema(description = "대표 영상 ID. 개별 재생 진입 키", example = "1042")
	Long videoId,

	@Schema(description = "썸네일 presigned GET URL. 대표는 항상 READY 라 null 이 아니다")
	String thumbnailUrl,

	@Schema(description = "영상 길이(초, 최대 30)", example = "12")
	Short durationSec,

	@Schema(description = "조회수 — 대표 선정 정렬 키", example = "37")
	Long viewCount,

	@Schema(description = "촬영 시각 (표시용). 정렬 tie-break 키는 createdAt 이다", example = "2026-07-20T18:03:11")
	LocalDateTime recordedAt
) {

	/** 썸네일 presigned GET URL 은 서비스가 발급해 넘긴다 (엔티티엔 S3 key 만 있어서다). */
	public static GridCoverVideoResponseDto of(Video video, String thumbnailUrl) {
		return new GridCoverVideoResponseDto(
			video.getId(),
			thumbnailUrl,
			video.getDurationSec(),
			video.getViewCount(),
			video.getRecordedAt());
	}
}

package com.msg.fillmap.video.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.entity.Video;

/**
 * 격자별 내 영상 리스트 항목 (MSG-127). 격자를 탭했을 때 그 격자에 내가 올린 영상 하나를 표현한다.
 * thumbnailUrl 은 S3 key 가 아니라 발급된 presigned GET URL 이며, READY 이전(썸네일 key 없음)이면 null 이다.
 */
@Schema(description = "격자별 내 영상 리스트 항목",
	requiredProperties = {"videoId", "processingStatus", "durationSec", "createdAt", "thumbnailUrl"})
public record GridVideoResponseDto(
	@Schema(description = "영상(방문 이벤트) ID. 개별 재생·교체·삭제 진입 키", example = "1042")
	Long videoId,

	@Schema(description = "썸네일 presigned GET URL. READY 아니면(썸네일 key 없음) null", nullable = true)
	String thumbnailUrl,

	@Schema(description = "영상 처리 상태 (UPLOADED/ENCODING/BLURRING/READY/FAILED)", example = "READY")
	String processingStatus,

	@Schema(description = "영상 길이(초, 최대 30)", example = "12")
	Short durationSec,

	@Schema(description = "업로드(방문) 시각 — 정렬 키", example = "2026-07-20T18:03:11")
	LocalDateTime createdAt
) {

	/** 썸네일 presigned GET URL 은 서비스가 발급해 넘긴다 (엔티티엔 S3 key 만 있어서다). */
	public static GridVideoResponseDto of(Video video, String thumbnailUrl) {
		return new GridVideoResponseDto(
			video.getId(),
			thumbnailUrl,
			video.getProcessingStatus().name(),
			video.getDurationSec(),
			video.getCreatedAt());
	}
}

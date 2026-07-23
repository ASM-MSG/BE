package com.msg.fillmap.video.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.entity.Video;

/**
 * 단건 영상 재생 조회 응답 (MSG-206). 영상 하나의 표시용 메타 + 재생본 presigned GET URL 을 담는다.
 * playbackUrl·thumbnailUrl 은 S3 key 가 아니라 발급된 presigned GET URL 이며, 서비스가 접근 제어·처리상태에
 * 따라 발급 여부를 정한다(엔티티엔 S3 key 만 있어서다). status 는 소유자가 블라인드 사유(재생 불가)를
 * playbackUrl=null 만으로 구분 못 하는 걸 해소하는 축이다(§설계 M5).
 */
@Schema(description = "단건 영상 재생 조회 응답")
public record VideoPlaybackResponseDto(
	@Schema(description = "영상(방문 이벤트) ID", example = "1042")
	Long videoId,

	@Schema(description = "재생본 presigned GET URL. READY 아님·BLINDED(소유자)면 null", nullable = true)
	String playbackUrl,

	@Schema(description = "썸네일 presigned GET URL. 썸네일 key 없음(READY 이전)이면 null", nullable = true)
	String thumbnailUrl,

	@Schema(description = "이 영상이 속한 격자 ID", example = "41642_110458")
	String gridId,

	@Schema(description = "영상 길이(초, 최대 30)", example = "12")
	Short durationSec,

	@Schema(description = "영상 처리 상태 (UPLOADED/ENCODING/BLURRING/READY/FAILED)", example = "READY")
	String processingStatus,

	@Schema(description = "공개 범위 (PUBLIC/PRIVATE)", example = "PUBLIC")
	String visibility,

	@Schema(description = "영상 상태 (ACTIVE/BLINDED). 소유자가 블라인드 사유를 구분하는 축", example = "ACTIVE")
	String status,

	@Schema(description = "조회수 (이번 조회 증가 전 스냅샷)", example = "37")
	Long viewCount,

	@Schema(description = "촬영 시각 (표시용)", example = "2026-07-20T18:03:11")
	LocalDateTime recordedAt,

	@Schema(description = "playbackUrl presign TTL(초). playbackUrl=null 이면 null", nullable = true)
	Long expiresInSec
) {

	/** 재생/썸네일 presigned GET URL 과 TTL 은 서비스가 발급해 넘긴다 (엔티티엔 S3 key 만 있어서다). */
	public static VideoPlaybackResponseDto of(Video video, String playbackUrl, String thumbnailUrl, Long expiresInSec) {
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
			expiresInSec);
	}
}

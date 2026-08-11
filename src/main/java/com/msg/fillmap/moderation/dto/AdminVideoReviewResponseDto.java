package com.msg.fillmap.moderation.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.entity.Visibility;

/**
 * 관리자 단건 영상 확인 응답 (MSG-195 FR-3). 신고 판단용이라 status(BLINDED 포함)·visibility 를 무시하고
 * 확인 요청 시점에 재생 URL 을 발급한다 — 목록에 실으면 발급과 시청 사이에 presigned URL 이 만료된다.
 * 사용자 재생 응답(VideoPlaybackResponseDto)과 달리 조회수·격자 필드가 없다 — 관리자 확인은 조회수를
 * 올리지 않고, 판단에 필요한 축만 담는다.
 */
@Schema(
	description = "관리자 단건 영상 확인 응답 — 영상 메타와 재생·썸네일 presigned GET URL.",
	// 값이 null 일 수 있는 URL·TTL 필드도 required 다 — Jackson 기본 직렬화라 키는 항상 나가고,
	// OpenAPI required 는 "키 존재"라서 nullable 과 직교한다 (MSG-319 계약 가드).
	requiredProperties = {"videoId", "status", "processingStatus", "visibility", "durationSec", "recordedAt",
		"playbackUrl", "thumbnailUrl", "expiresInSec"}
)
public record AdminVideoReviewResponseDto(
	@Schema(description = "영상 ID", example = "1042")
	Long videoId,

	@Schema(description = "영상 상태 — BLINDED 여도 발급된다 (DELETED 만 404)", example = "BLINDED")
	VideoStatus status,

	@Schema(description = "영상 처리 상태 — READY 일 때만 재생 URL 이 발급된다", example = "READY")
	ProcessingStatus processingStatus,

	@Schema(description = "공개 범위 — PRIVATE 여도 발급된다 (관리자 확인은 은닉 없음)", example = "PRIVATE")
	Visibility visibility,

	@Schema(description = "영상 길이(초, 최대 30)", example = "12")
	Short durationSec,

	@Schema(description = "촬영 시각 (표시용)", example = "2026-07-20T18:03:11Z")
	LocalDateTime recordedAt,

	@Schema(description = "재생본 presigned GET URL — READY 가 아니면 null", nullable = true)
	String playbackUrl,

	@Schema(description = "썸네일 presigned GET URL — 썸네일 key 없음(READY 이전)이면 null", nullable = true)
	String thumbnailUrl,

	@Schema(description = "playbackUrl presign TTL(초) — playbackUrl=null 이면 null", nullable = true, example = "600")
	Long expiresInSec
) {

	/** URL 과 TTL 은 서비스가 발급해 넘긴다 — 엔티티엔 S3 key 만 있어서다 (VideoPlaybackResponseDto 선례). */
	public static AdminVideoReviewResponseDto of(Video video, String playbackUrl, String thumbnailUrl,
		Long expiresInSec) {
		return new AdminVideoReviewResponseDto(
			video.getId(),
			video.getStatus(),
			video.getProcessingStatus(),
			video.getVisibility(),
			video.getDurationSec(),
			video.getRecordedAt(),
			playbackUrl,
			thumbnailUrl,
			expiresInSec);
	}
}

package com.msg.fillmap.video.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.entity.Video;

/**
 * 친구 격자 영상 리스트 항목 (MSG-187 D5). 친구의 격자를 탭했을 때 그 친구가 그 격자에 올린 영상 하나를
 * 표현한다. 목록에는 재생 가능한 영상(ACTIVE·READY·PUBLIC/FRIENDS)만 담기므로 processingStatus 는
 * 항상 READY 라 계약에서 뺐다. thumbnailUrl 은 S3 key 가 아니라 발급된 presigned GET URL 이다.
 */
@Schema(description = "친구 격자 영상 리스트 항목", requiredProperties = {"videoId", "durationSec", "createdAt", "thumbnailUrl"})
public record FriendGridVideoResponseDto(
	@Schema(description = "영상(방문 이벤트) ID. 재생 조회 진입 키", example = "1042")
	Long videoId,

	@Schema(description = "썸네일 presigned GET URL. 썸네일 key 가 없으면 null", nullable = true)
	String thumbnailUrl,

	@Schema(description = "영상 길이(초, 최대 30)", example = "12")
	Short durationSec,

	@Schema(description = "업로드(방문) 시각 — 정렬 키", example = "2026-07-20T18:03:11")
	LocalDateTime createdAt
) {

	/** 썸네일 presigned GET URL 은 서비스가 발급해 넘긴다 (엔티티엔 S3 key 만 있어서다). */
	public static FriendGridVideoResponseDto of(Video video, String thumbnailUrl) {
		return new FriendGridVideoResponseDto(
			video.getId(),
			thumbnailUrl,
			video.getDurationSec(),
			video.getCreatedAt());
	}
}

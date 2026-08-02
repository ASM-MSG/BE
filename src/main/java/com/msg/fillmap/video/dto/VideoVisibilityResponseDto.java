package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.entity.Video;

/**
 * 영상 공개 범위 전환 응답 (MSG-162). FE 가 토글 UI 를 확정 상태로 갱신하도록 전환 후 상태를 돌려준다.
 */
@Schema(description = "영상 공개 범위 전환 응답. 전환 후 공개 범위를 담는다.",
	requiredProperties = {"videoId", "visibility"})
public record VideoVisibilityResponseDto(
	@Schema(description = "전환된 영상 ID", example = "1042")
	Long videoId,

	@Schema(description = "전환 후 공개 범위 (PUBLIC 또는 PRIVATE)", example = "PUBLIC")
	String visibility
) {

	public static VideoVisibilityResponseDto from(Video video) {
		return new VideoVisibilityResponseDto(video.getId(), video.getVisibility().name());
	}
}

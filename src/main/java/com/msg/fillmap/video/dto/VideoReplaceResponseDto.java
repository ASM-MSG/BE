package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.video.entity.Video;

/**
 * 교체 응답 (MSG-71). 교체 직후는 항상 재인코딩 대기(UPLOADED)이므로 클라이언트가 그 상태를 보고
 * "변환 중" UI 를 그릴 수 있어야 한다.
 */
@Schema(description = "영상 교체 응답. 교체 직후는 항상 재인코딩 대기(UPLOADED) 상태다.")
public record VideoReplaceResponseDto(
	@Schema(description = "교체된 영상 ID", example = "1001")
	Long videoId,

	@Schema(description = "영상 처리 상태 (교체 직후 UPLOADED)", example = "UPLOADED")
	String processingStatus
) {

	public static VideoReplaceResponseDto from(Video video) {
		return new VideoReplaceResponseDto(video.getId(), video.getProcessingStatus().name());
	}
}

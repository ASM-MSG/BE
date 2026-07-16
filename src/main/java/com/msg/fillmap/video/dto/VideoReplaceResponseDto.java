package com.msg.fillmap.video.dto;

import com.msg.fillmap.video.entity.Video;

/**
 * 교체 응답 (MSG-71). 교체 직후는 항상 재인코딩 대기(UPLOADED)이므로 클라이언트가 그 상태를 보고
 * "변환 중" UI 를 그릴 수 있어야 한다.
 */
public record VideoReplaceResponseDto(
	Long videoId,
	String processingStatus
) {

	public static VideoReplaceResponseDto from(Video video) {
		return new VideoReplaceResponseDto(video.getId(), video.getProcessingStatus().name());
	}
}

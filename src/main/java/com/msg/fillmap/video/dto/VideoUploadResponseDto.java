package com.msg.fillmap.video.dto;

/**
 * 메타데이터 저장 응답. occupied = 이 업로드로 격자를 처음 점령했는지(첫 방문) 여부.
 */
public record VideoUploadResponseDto(
	Long videoId,
	String gridId,
	String processingStatus,
	boolean occupied
) {
}

package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 메타데이터 저장 응답. occupied = 이 업로드로 격자를 처음 점령했는지(첫 방문) 여부.
 */
@Schema(description = "영상 메타데이터 저장 응답")
public record VideoUploadResponseDto(
	@Schema(description = "생성된 영상 ID", example = "1001")
	Long videoId,

	@Schema(description = "매핑된 격자 ID", example = "41642_110458")
	String gridId,

	@Schema(description = "영상 처리 상태 (UPLOADED/ENCODING/BLURRING/READY/FAILED)", example = "UPLOADED")
	String processingStatus,

	@Schema(description = "이 업로드로 격자를 처음 점령(첫 방문)했는지 여부", example = "true")
	boolean occupied
) {
}

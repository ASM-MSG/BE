package com.msg.fillmap.event.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 행사 영상 업로드 확정 요청 (MSG-440 §API 1). 촬영과 갤러리는 클라이언트 구분일 뿐 서버 계약은 하나다.
 * 좌표가 없다 — 격자는 서버가 그 위치의 대표 격자로 정한다(FR-EVENT-08, GPS 검증 없음). 제목·설명도 없고
 * 공개범위는 서버가 PUBLIC 으로 고정한다(행사 피드는 공개 전제, MSG-438 확정).
 * s3Key 는 기존 presigned 발급(POST /api/videos/presigned-url) 응답의 pending 키이며 멱등 키를 겸한다.
 */
@Schema(description = "행사 영상 업로드 확정 요청")
public record EventVideoUploadRequestDto(

	@Schema(description = "presigned 발급 때 받은 S3 객체 키. 같은 키로 다시 보내면 멱등하게 처리된다",
		example = "videos/pending/42/6f1c1f0e-1d2b-4a5a-9f0e-2b3c4d5e6f70.mp4")
	@NotBlank
	String s3Key,

	@Schema(description = "영상 길이(초). 1~30초", example = "15")
	@NotNull
	@Min(1)
	@Max(30)
	Short durationSec,

	@Schema(description = "촬영 시각. 미래 시각은 거부된다(단말 시계 오차 5분 허용)", example = "2026-10-06T12:30:00Z")
	@NotNull
	LocalDateTime recordedAt
) {
}

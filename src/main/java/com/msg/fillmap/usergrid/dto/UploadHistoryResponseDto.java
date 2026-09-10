package com.msg.fillmap.usergrid.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.usergrid.service.UploadHistoryView;

/**
 * 날짜별 업로드 기록 항목 응답 (MSG-362). 도감 화면 "스트릭 전체 기록 보기"의 잔디 재료 — 업로드가 있었던
 * KST 날짜와 그날의 건수만 담고, 빈 날은 항목 자체가 없다(빈 칸 채우기는 FE 몫). 삭제·블라인드 영상의
 * 업로드도 센다(스트릭 소급 차감 없음과 정합).
 */
@Schema(description = "날짜별 업로드 기록 항목 — 업로드가 있었던 KST 날짜 하나와 그날의 건수.",
	requiredProperties = {"uploadDate", "uploadCount"})
public record UploadHistoryResponseDto(
	@Schema(description = "업로드가 있었던 KST 날짜", example = "2026-08-11")
	LocalDate uploadDate,

	@Schema(description = "그날 업로드한 영상 수 (1 이상)", example = "3")
	Integer uploadCount
) {

	public static UploadHistoryResponseDto from(UploadHistoryView view) {
		return new UploadHistoryResponseDto(view.uploadDate(), view.uploadCount());
	}
}

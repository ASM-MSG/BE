package com.msg.fillmap.hotzone.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.hotzone.service.HotZoneView;

/**
 * 핫구역 목록 응답 (MSG-184). 핫스코어 내림차순 — 없으면 빈 목록 (FR-9).
 */
@Schema(description = "뷰포트 내 핫구역 목록 응답 (핫스코어 내림차순)")
public record HotZoneListResponseDto(
	@Schema(description = "핫구역 목록 — 핫스코어 내림차순. 없으면 빈 배열")
	List<HotZoneResponseDto> hotZones
) {

	public static HotZoneListResponseDto from(List<HotZoneView> views) {
		return new HotZoneListResponseDto(views.stream()
			.map(HotZoneResponseDto::from)
			.toList());
	}
}

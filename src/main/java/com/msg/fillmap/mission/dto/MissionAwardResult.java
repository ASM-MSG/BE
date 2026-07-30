package com.msg.fillmap.mission.dto;

import java.util.List;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;

/**
 * 미션 판정 결과 (MSG-223 §구현 구성). API DTO 가 아니라 video 훅에 돌려주는 내부 결과 쌍이다
 * (MissionShape 처럼 접미 없음) — completedMissions 는 응답 completedMissions 로, newBadges 는
 * 기존 newBadges 배열에 합류한다(FR-17·19). 지배 경로(미션 미해당 업로드)는 EMPTY.
 */
public record MissionAwardResult(
	List<CompletedMissionResponseDto> completedMissions,
	List<EarnedBadgeResponseDto> newBadges
) {

	public static final MissionAwardResult EMPTY = new MissionAwardResult(List.of(), List.of());
}

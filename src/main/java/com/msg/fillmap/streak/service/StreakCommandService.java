package com.msg.fillmap.streak.service;

import java.util.List;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;

/**
 * 스트릭 집계 (MSG-200). B 내부 서비스 — 계약 인터페이스 아님. metric(current_count)의 원천이
 * streaks 이므로 꾸준함 뱃지(STREAK_DAYS) 판정 배선까지 이 도메인 몫이다(§D7) — video 훅은
 * 반환된 획득분을 응답 newBadges 에 합류시키는 1줄만 안다.
 */
public interface StreakCommandService {

	/**
	 * 업로드 이벤트의 스트릭 갱신 — 아무 업로드(재방문 포함)가 인정 이벤트다(§D1). KST 일 단위로
	 * 같은 날 no-op / 어제 +1 / 그 외 1 리셋 후, 갱신된 연속 일수로 꾸준함 뱃지를 판정한다.
	 * 업로드 트랜잭션에 참여한다(§D5).
	 *
	 * @return 이번 호출로 새로 획득한 뱃지 (없으면 빈 리스트 — 지배적 경로)
	 */
	List<EarnedBadgeResponseDto> recordUpload(long userId);
}

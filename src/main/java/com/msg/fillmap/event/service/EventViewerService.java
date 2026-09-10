package com.msg.fillmap.event.service;

/**
 * 행사방 열람 인원 집계 (MSG-443). 클라이언트가 30초 주기로 보내는 heartbeat 를 받아
 * 마지막 신호가 90초 이내인 고유 세션만 센다. 저장은 Redis 뿐이고 유실을 허용하는 근사값이다.
 */
public interface EventViewerService {

	/**
	 * 열람 세션 갱신. 캐시 예외는 삼킨다(warn 로그) — 30초 뒤 재전송이 내장돼 있다 (D3).
	 *
	 * @param occurrenceId 행사 회차 id (존재 검증 없음 — MSG-438 후속)
	 * @param viewerKey 세션 식별자. 로그인 {@code u:{userId}}, 비로그인 {@code s:{sessionId}} (D4)
	 */
	void recordHeartbeat(long occurrenceId, String viewerKey);

	/**
	 * 현재 열람 인원. 0 = 아무도 없음(정상 집계), null = 캐시 장애(FE 가 문구를 숨긴다) (D3).
	 */
	Integer getViewerCount(long occurrenceId);
}

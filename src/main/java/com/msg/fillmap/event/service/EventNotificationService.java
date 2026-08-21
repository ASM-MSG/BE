package com.msg.fillmap.event.service;

import java.time.LocalDateTime;

import com.msg.fillmap.event.dto.EventNotificationResponseDto;

/**
 * 행사 회차 알림 구독 (MSG-442, FR-EVENT-06). 행사방에는 참여 절차가 없으므로 구독이 곧 사용자와 행사의
 * 관계 전부다.
 */
public interface EventNotificationService {

	/** 구독 ON/OFF. 같은 값의 반복 요청은 같은 결과로 성공한다 (멱등). */
	EventNotificationResponseDto updateSubscription(long userId, long occurrenceId, boolean enabled);

	/**
	 * 노출 상태 (MSG-439 헤더 응답의 알림 상태 필드가 소비) — 구독 행 존재 AND 회차 상태가 예정·진행 중.
	 * 비로그인(userId null)은 항상 false 다. 판정 시각은 이 서비스의 Clock 이다.
	 */
	boolean isSubscribed(Long userId, Long occurrenceId);

	/**
	 * 판정 시각을 호출자가 정하는 변형. 이미 자기 Clock 으로 {@code now} 를 고정한 호출자(조회 서비스)가
	 * 쓴다 — 한 응답 안에서 status 는 호출자 시각, notificationOn 은 이 서비스 시각으로 갈리면 경계
	 * 근처에서 "진행 중인데 구독이 꺼져 보이는" 모순이 난다. 파생식 자체는 여전히 여기 한 곳에만 있다.
	 */
	boolean isSubscribed(Long userId, Long occurrenceId, LocalDateTime now);
}

package com.msg.fillmap.notification.service;

import com.msg.fillmap.notification.dto.PushTokenRequestDto;

/**
 * FCM 푸시 토큰 등록/해제 (MSG-178). 알림 발송 파이프라인(MSG-179~181)의 토큰 수집 창구 —
 * 178의 읽기 경로는 0이다.
 */
public interface PushTokenService {

	/**
	 * 토큰 등록/갱신 (FR-1) — UPSERT 단일 문장. 같은 fcm_token 재등록은 충돌 없이
	 * user_id·platform·app_version·last_used_at 을 갱신한다(계정 전환 이관 포함).
	 * platform 파싱(대소문자 무시) 실패 시 9400 INVALID_PLATFORM.
	 */
	void register(long userId, PushTokenRequestDto request);

	/** 토큰 해제 (FR-2) — 멱등. 행이 있으면 삭제, 없어도 성공. */
	void unregister(String fcmToken);
}

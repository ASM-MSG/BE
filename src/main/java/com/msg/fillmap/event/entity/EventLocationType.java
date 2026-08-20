package com.msg.fillmap.event.entity;

/**
 * 행사 위치 종류 (MSG-438). 넷은 PRD §2 예시고 ETC 는 공연장처럼 넷에 안 걸리는 위치의 도피처다.
 * 표시 라벨 변환은 FE 몫 — MSG-439 는 enum 문자열을 그대로 내려보낸다.
 */
public enum EventLocationType {

	POPUP,
	EXPERIENCE_ZONE,
	PARADE,
	PHOTO_ZONE,
	ETC
}

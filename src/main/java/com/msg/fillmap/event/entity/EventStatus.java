package com.msg.fillmap.event.entity;

/**
 * 행사 회차의 파생 상태 (MSG-438). 저장 컬럼이 아니라 starts_at·ends_at 와 서버 시각으로만 계산된다 —
 * 정의는 {@link EventOccurrence#statusAt} 한 곳이고 MSG-439 조회 응답이 그 값을 그대로 쓴다.
 */
public enum EventStatus {

	/** 시작 전. */
	UPCOMING,

	/** 시작~종료. */
	LIVE,

	/** 종료 후 업로드 유예(30일) — 영상 업로드는 되고 댓글·도움돼요는 막힌다. */
	UPLOAD_GRACE,

	/** 유예 종료 이후 — 읽기 전용 아카이브. */
	ARCHIVED
}

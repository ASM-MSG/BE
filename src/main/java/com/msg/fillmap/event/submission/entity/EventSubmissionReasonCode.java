package com.msg.fillmap.event.submission.entity;

/**
 * 반려 항목 코드 (MSG-498 FR-19). 관리자가 무엇 때문에 반려했는지를 화면이 항목 카드로 그린다.
 * 이 티켓은 저장 형태 정의와 읽기만 담당하고, 쓰기(1개 이상 강제 검증 포함)는 MSG-500 이다.
 */
public enum EventSubmissionReasonCode {

	/** 행사 기간. */
	PERIOD,

	/** 위치 영역. */
	AREA,

	/** 대표 이미지. */
	IMAGE,

	/** 기본 정보. */
	INFO
}

package com.msg.fillmap.event.submission.entity;

/**
 * 행사 등재 신청의 등록 유형 (MSG-498, PRD 유형 표). 유형이 유형별 필수 항목을 결정한다 —
 * FESTIVAL 은 주요 프로그램(programDescription), POPUP 은 운영 시간(operatingHours)이다.
 * 세 번째 유형(이벤트 참여형)은 v2.1 재편으로 MSG-501·502 로 분리돼 여기 없다 — 그쪽이 값을 추가한다.
 */
public enum EventSubmissionType {

	/** 지역축제. */
	FESTIVAL,

	/** 팝업스토어. */
	POPUP
}

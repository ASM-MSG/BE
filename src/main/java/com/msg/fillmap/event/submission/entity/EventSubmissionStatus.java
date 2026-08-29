package com.msg.fillmap.event.submission.entity;

/**
 * 행사 등재 신청 상태 (MSG-498 FR-10). 이 티켓이 만드는 전이는 제출 시 IN_REVIEW 와 반려본 재제출 시
 * REJECTED → IN_REVIEW 둘뿐이고, APPROVED · REJECTED 로 보내는 쪽은 관리자 심사(MSG-500)다.
 */
public enum EventSubmissionStatus {

	/** 심사 중. 제출·재제출 직후의 상태다. */
	IN_REVIEW,

	/** 승인. 쓰는 쪽은 MSG-500. */
	APPROVED,

	/** 반려. 이 상태에서만 수정 재제출이 열린다 (FR-13). */
	REJECTED
}

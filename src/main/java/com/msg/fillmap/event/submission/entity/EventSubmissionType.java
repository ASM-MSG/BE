package com.msg.fillmap.event.submission.entity;

/**
 * 행사 등재 신청의 등록 유형 (MSG-498, PRD 유형 표). 유형이 유형별 필수 항목을 결정한다 —
 * FESTIVAL 은 주요 프로그램(programDescription), POPUP 은 운영 시간(operatingHours),
 * EVENT 는 참여 방식(participationMethod)과 부모 회차(parentEventOccurrenceId)다.
 */
public enum EventSubmissionType {

	/** 지역축제. */
	FESTIVAL,

	/** 팝업스토어. */
	POPUP,

	/**
	 * 이벤트 참여형 (MSG-502) — 승인된 이벤트 회차 아래에 참여를 붙이는 신청이다.
	 * <p>
	 * 여기서 말하는 "이벤트"는 glossary 의 이벤트 카테고리(지역축제·팝업을 제외한 큰 행사)이고,
	 * 이름이 같은 {@code MissionType.EVENT}(축제 미션)와는 반대편을 가리킨다. 게다가 이 콘솔에서
	 * 승인된 <b>지역축제</b> 신청이 {@code MissionType.EVENT} 미션이 되는 교차까지 있으므로
	 * (PRD v2.2 확정 2) 두 상수를 같은 것으로 읽지 않는다.
	 */
	EVENT
}

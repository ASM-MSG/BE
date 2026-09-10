package com.msg.fillmap.user.entity;

/**
 * 아이디(공식 이메일) 변경 요청의 처리 상태 (MSG-497). V46 의 chk_org_email_change_requests_status 와
 * 같은 값 3종이다 — 이 티켓은 PENDING 만 만들고, APPROVED·REJECTED 는 관리자 처리(MSG-500)가 쓴다.
 */
public enum OrgEmailChangeStatus {

	PENDING,
	APPROVED,
	REJECTED
}

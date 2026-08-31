package com.msg.fillmap.user.entity;

/**
 * 행사 운영자 계정 발급 요청의 처리 상태 (MSG-499 FR-6). PENDING 만 부분 유니크 인덱스
 * (uq_org_account_requests_pending)의 대상이라, 처리가 끝난 이메일은 새 대기 요청을 다시 만들 수 있다
 * (반려 후 재신청·오타 주소 재신청 경로).
 */
public enum OrgAccountRequestStatus {

	PENDING,
	ISSUED,
	REJECTED
}

package com.msg.fillmap.moderation.entity;

/**
 * 신고 처리 상태 (reports.status). V1 DDL CHECK(chk_reports_status)와 문자열까지 일치 (MSG-192).
 * 접수는 PENDING 만 만들고, 나머지 전이는 관리자 처리(MSG-195) 몫이다.
 */
public enum ReportStatus {
	PENDING,
	REVIEWING,
	RESOLVED,
	REJECTED
}

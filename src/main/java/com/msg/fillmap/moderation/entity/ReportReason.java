package com.msg.fillmap.moderation.entity;

/**
 * 신고 사유 (reports.reason). V1 DDL CHECK(chk_reports_reason)와 문자열까지 일치 (MSG-192).
 * OTHER 는 상세 설명이 필수다 — 사유별 조건이라 DB 가 아니라 서비스가 검사한다.
 */
public enum ReportReason {
	INAPPROPRIATE,
	PRIVACY,
	SPAM,
	COPYRIGHT,
	OTHER
}

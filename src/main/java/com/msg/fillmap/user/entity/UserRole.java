package com.msg.fillmap.user.entity;

public enum UserRole {
	USER,
	// 행사 운영자 — 행사를 등록하는 외부 주체(지자체·팝업 운영사·축제 대행사). 지자체 중립이다 (MSG-496, glossary.md).
	ORG,
	ADMIN
}

package com.msg.fillmap.route.service;

import java.time.LocalDateTime;

/**
 * 경로 추천 후보 지점 하나 (MSG-457 §도메인 로직 1). 서버 조회 세 출처(활성 미션·행사·장소 검색 실조회)
 * 에서만 만들어진다 — 해석 결과의 문자열이 이 record 를 직접 만들 수 없는 것이 FR-ROUTE-03 의 구조적
 * 보장이다. period 두 값은 기간 겹침 필터와 facts 문장 재료(장소 검색 후보는 null), matchedInterest 는
 * 선별 우선순위와 "관심사 일치" facts 재료다.
 */
public record RouteCandidate(
	String name,
	Kind kind,
	double lat,
	double lng,
	String gridId,
	Long missionId,
	Long occurrenceId,
	LocalDateTime periodStart,
	LocalDateTime periodEnd,
	String matchedInterest
) {

	/** 응답 kind 다섯 값 (FE 마커 분기 계약, §API 응답 표). explain 요청에는 소문자로 나간다(이유 문장화 절). */
	public enum Kind {
		MISSION_FESTIVAL,
		MISSION_POPUP,
		MISSION_COURSE,
		EVENT,
		PLACE
	}
}

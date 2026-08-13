package com.msg.fillmap.mission.seed;

import java.time.LocalDate;

/**
 * popups.jsonl 1행에서 추출한 적재 입력 (MSG-235 D1 · MSG-383 D3). id 는 팝가 외부 안정 id —
 * source_key 멱등 키(D3). 날짜는 KST 달력 날짜다. periodType 은 계속 의도적 미사용이다
 * (주 1회 스냅샷 스큐, MSG-235 D6).
 *
 * placeName 은 도로명주소(+상세)를 reader 가 조립한 값이고, operationTime 은 문자열 배열을 개행으로
 * 이어 붙인 안내 문구다. 소개문(description)은 현 스냅샷에 필드 자체가 없어 이 티켓에서 null 로 남는다
 * — 팝가 상세 페이지 수집이 MSG-384 몫이다.
 */
public record PopupRecord(long id, String name, double latitude, double longitude, LocalDate openDate,
	LocalDate closeDate, String placeName, String sourceUrl, String operationTime) {
}

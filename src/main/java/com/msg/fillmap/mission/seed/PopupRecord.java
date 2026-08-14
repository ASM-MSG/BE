package com.msg.fillmap.mission.seed;

import java.time.LocalDate;

/**
 * popups.jsonl 1행에서 추출한 적재 입력 (MSG-235 D1 · MSG-383 D3). id 는 팝가 외부 안정 id —
 * source_key 멱등 키(D3). 날짜는 KST 달력 날짜다. periodType 은 계속 의도적 미사용이다
 * (주 1회 스냅샷 스큐, MSG-235 D6).
 *
 * placeName 은 도로명주소(+상세)를 reader 가 조립한 값이고, operationTime 은 문자열 배열을 개행으로
 * 이어 붙인 안내 문구다. 소개문(description)과 포스터(imageKey)는 상세 페이지 보강 수집 산출이라
 * MSG-394 부터 실린다 — 개별 팝업의 결측은 정상이다(둘의 성공 판정이 필드 단위, D1).
 *
 * imageKey 는 완성된 주소가 아니라 <b>버킷 상대 키</b>다 (FestivalRecord 와 같은 이유) — 외부 도메인이
 * 저장되는 상태를 표현 불가능하게 만들고, 같은 스냅샷 파일을 버킷이 다른 dev·prod 양쪽에 그대로 쓴다.
 * 공개 주소 조립은 시더 몫이다.
 */
public record PopupRecord(long id, String name, double latitude, double longitude, LocalDate openDate,
	LocalDate closeDate, String description, String placeName, String sourceUrl, String operationTime,
	String imageKey) {
}

package com.msg.fillmap.mission.seed;

import java.time.LocalDate;

/**
 * popups.jsonl 1행에서 추출한 적재 입력 (MSG-235 D1). 적재에 쓰는 필드만 담는다 — operationTime·address
 * 등 나머지는 missions 에 컬럼이 없어 미적재, periodType 은 의도적 미사용(주 1회 스냅샷 스큐, D6).
 * id 는 팝가 외부 안정 id — source_key 멱등 키(D3). 날짜는 KST 달력 날짜다.
 */
public record PopupRecord(long id, String name, double latitude, double longitude, LocalDate openDate,
	LocalDate closeDate) {
}

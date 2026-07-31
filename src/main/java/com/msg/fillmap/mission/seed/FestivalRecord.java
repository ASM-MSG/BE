package com.msg.fillmap.mission.seed;

import java.time.LocalDate;

/**
 * festivals.jsonl 1행에서 추출한 적재 입력 (MSG-224 D1). 적재에 쓰는 필드만 담는다 —
 * place·description 등 나머지는 missions 에 컬럼이 없어 미적재(신규 DDL 금지). 날짜는 KST 달력 날짜다.
 */
public record FestivalRecord(String name, double latitude, double longitude, LocalDate startDate, LocalDate endDate) {
}

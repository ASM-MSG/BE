package com.msg.fillmap.mission.seed;

import java.time.LocalDate;

/**
 * festivals.jsonl 1행에서 추출한 적재 입력 (MSG-224 D1 · MSG-383 D3 · MSG-384 D2). 적재에 쓰는 필드만
 * 담는다 — description·place·homepage 는 MSG-383 이 V31 컬럼과 함께 수용했고, roadAddress·lotAddress·
 * referenceDate·sourceOrg 는 계속 미적재다(화면이 위치 한 줄만 요구, D3). 날짜는 KST 달력 날짜다.
 *
 * imageKey 는 완성된 주소가 아니라 <b>버킷 상대 키</b>다 — 외부 도메인이 저장되는 상태를 표현 불가능하게
 * 만들고(MSG-384 D2), 같은 스냅샷 파일을 서로 다른 버킷을 쓰는 dev·prod 양쪽에 그대로 쓴다.
 * 공개 주소 조립은 시더 몫이다.
 */
public record FestivalRecord(String name, double latitude, double longitude, LocalDate startDate, LocalDate endDate,
	String description, String place, String homepage, String imageKey) {
}

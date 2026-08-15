package com.msg.fillmap.usergrid.dto;

/**
 * 도감 갤러리 격자 정렬 (MSG-388). 와이어 값은 대문자 전용 — Spring MVC enum 바인딩이 대소문자 민감이라
 * 소문자 포함 무효 값은 MethodArgumentTypeMismatchException → 400 전역 매핑으로 떨어진다(조용한 기본값
 * 폴백 금지 — FE 버그 은폐, ExploreSort 선례). 기본값은 컨트롤러 defaultValue = "COLLECTED" 라
 * 파라미터 없는 기존 호출의 정렬 계약이 그대로 보존된다(FR-7).
 * 전역 탐색의 LATEST("격자의 최신 공개 영상 시각")와 이름을 가른 이유는 축이 다르기 때문이다 —
 * 여기 UPLOADED 는 내 업로드 축(user_grids.last_uploaded_at)이다.
 */
public enum CollectionGridSort {

	/** 수집순 — 내가 점령한 시각 내림차순 (동률: gridId 내림차순). 기존 갤러리 정렬이자 기본값. */
	COLLECTED,

	/** 최신 업로드순 — 그 격자에 마지막으로 올린 시각 내림차순 (동률: gridId 내림차순). */
	UPLOADED
}

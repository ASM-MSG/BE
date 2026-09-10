package com.msg.fillmap.video.dto;

/**
 * 전역 탐색 격자 카드 정렬 (MSG-238 §D3). 와이어 값은 대문자 전용 — Spring MVC enum 바인딩이 대소문자
 * 민감이라 소문자(popular) 포함 무효 값은 MethodArgumentTypeMismatchException → 400 전역 매핑으로
 * 떨어진다(조용한 기본값 폴백 금지 — FE 버그 은폐). 기본값은 컨트롤러 defaultValue = "POPULAR".
 */
public enum ExploreSort {

	/** 인기순 — 격자 공개 영상 조회수 합 내림차순 (동률: 영상 수 → gridId 내림차순). */
	POPULAR,

	/** 최신순 — 격자의 최신 공개 영상 시각 내림차순 (동률: gridId 내림차순). */
	LATEST
}

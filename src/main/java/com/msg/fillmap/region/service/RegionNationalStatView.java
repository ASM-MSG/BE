package com.msg.fillmap.region.service;

/**
 * 전국 탐험률 재료 내부 뷰 (MSG-406). RegionStatView(MSG-156) 선례대로 리포지토리 프로젝션을 컨트롤러로
 * 그대로 흘리지 않고 서비스가 이 뷰로 감싼다 — 응답 DTO 변환은 컨트롤러 책임.
 * 두 값 모두 반올림·clamp 없는 원값이고, 비율은 담지 않는다(§D-2·D-3).
 */
public record RegionNationalStatView(
	Long collectedCount,
	Long totalCount
) {
}

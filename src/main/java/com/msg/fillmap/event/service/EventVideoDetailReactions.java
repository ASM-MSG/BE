package com.msg.fillmap.event.service;

import com.msg.fillmap.event.dto.EventVideoCommentPageResponseDto;

/**
 * 영상 상세 한 건의 반응 재료 (MSG-441 §API 7). 상세가 네 필드를 한 번에 받으려고 묶은 event 도메인 내부
 * 타입이라 응답 DTO 가 아니다 — 상세 서비스가 자기 응답 레코드에 펼쳐 담는다.
 * 비로그인 요청이면 helpfulByMe 는 조회 없이 false 다.
 */
public record EventVideoDetailReactions(long helpfulCount, boolean helpfulByMe, long commentCount,
	EventVideoCommentPageResponseDto comments) {
}

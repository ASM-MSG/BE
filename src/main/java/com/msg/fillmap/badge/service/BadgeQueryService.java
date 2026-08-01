package com.msg.fillmap.badge.service;

import java.util.List;

import com.msg.fillmap.badge.dto.MyBadgeResponseDto;

/**
 * 내 뱃지 전체 목록 조회 (docs/MSG-201.md) — B 내부 서비스, 크로스오너 계약 인터페이스 아님.
 */
public interface BadgeQueryService {

	/**
	 * 시딩된 전체 뱃지를 내 획득 상태와 함께 badges.id 오름차순(시딩 순)으로 돌려준다.
	 * 부수효과: 이번 응답에 노출된 내 미확인 뱃지에 notified_at 을 스탬프한다 — 응답의 isNew 는
	 * 스탬프 전(SELECT 시점) 값이라 이번 응답에서는 "새 뱃지"로 보이고, 커밋 이후의 조회부터
	 * false 가 된다(docs/MSG-201.md §D3 조회 시 자동 확인 처리).
	 */
	List<MyBadgeResponseDto> findMyBadges(long userId);
}

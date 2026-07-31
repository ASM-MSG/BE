package com.msg.fillmap.badge.repository;

import java.time.LocalDateTime;

/**
 * 내 뱃지 목록 네이티브 조회 결과 프로젝션 (docs/MSG-201.md §D2). 마스터 전체(미획득 포함) + 내 획득
 * 상태 한 행 — 미획득이면 earned false 에 earnedAt·featuredRank 는 null, isNew 는 false 다.
 */
public interface MyBadgeProjection {

	Long getBadgeId();

	String getCode();

	String getName();

	String getDescription();

	String getIconUrl();

	Boolean getEarned();

	LocalDateTime getEarnedAt();

	Boolean getIsNew();

	Integer getFeaturedRank();
}

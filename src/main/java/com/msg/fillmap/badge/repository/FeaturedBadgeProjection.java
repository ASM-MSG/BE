package com.msg.fillmap.badge.repository;

/**
 * 대표 뱃지 네이티브 조회 결과 프로젝션 (MSG-239 §D6). rank = user_badges.featured_rank(1·2 표시 순서).
 */
public interface FeaturedBadgeProjection {

	Long getBadgeId();

	String getCode();

	String getName();

	String getIconUrl();

	Integer getRank();
}

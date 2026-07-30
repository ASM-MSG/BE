package com.msg.fillmap.badge.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.badge.entity.Badge;

/**
 * 뱃지 마스터 리포지토리 (MSG-239). 시딩은 V9 마이그레이션이 하므로 쓰기 메서드가 없다.
 */
public interface BadgeRepository extends JpaRepository<Badge, Long> {

	/**
	 * 지급 후보 조회 — 판정값(metric)을 충족했는데 아직 못 받은 티어 전부를 돌려준다 (한 행동으로 여러
	 * 티어를 동시에 충족하면 전부 지급하기 위해). condition_value 는 전 종류 공통 {"value": N} 포맷이라
	 * 이 판정식 하나가 격자 수·업로드 수·수집률 등 모든 종류에 그대로 쓰인다.
	 * 대부분의 행동은 0건으로 끝난다 — 판정 비용 = 인덱스드 카운트 + 이 SELECT 1회.
	 */
	@Query(value = """
		SELECT b.id AS "badgeId", b.code, b.name, b.description, b.icon_url AS "iconUrl"
		FROM badges b
		WHERE b.condition_type = :type
		  AND (b.condition_value->>'value')::numeric <= :metric
		  AND NOT EXISTS (SELECT 1 FROM user_badges ub WHERE ub.user_id = :userId AND ub.badge_id = b.id)
		ORDER BY (b.condition_value->>'value')::numeric
		""", nativeQuery = true)
	List<EligibleBadgeProjection> findEligible(
		@Param("type") String type,
		@Param("metric") BigDecimal metric,
		@Param("userId") long userId
	);
}

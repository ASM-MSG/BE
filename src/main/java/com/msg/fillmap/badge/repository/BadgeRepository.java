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

	/**
	 * 임박 후보 조회 (docs/MSG-314.md D1) — 같은 축에서 아직 못 받은 티어 중 condition_value 가 정확히
	 * metric + 1 인 뱃지. 티어 값은 축 안에서 서로 다르므로(V9·V10·V12 시딩) 결과는 호출당 최대 1건이다.
	 * 지급 조건(value <= metric)과 서로소라 한 뱃지가 획득·임박 둘 다에 걸릴 수 없다. SELECT 목록을
	 * findEligible 과 같게 맞춰 EligibleBadgeProjection 을 재사용한다.
	 */
	@Query(value = """
		SELECT b.id AS "badgeId", b.code, b.name, b.description, b.icon_url AS "iconUrl"
		FROM badges b
		WHERE b.condition_type = :type
		  AND (b.condition_value->>'value')::numeric = :metric + 1
		  AND NOT EXISTS (SELECT 1 FROM user_badges ub WHERE ub.user_id = :userId AND ub.badge_id = b.id)
		""", nativeQuery = true)
	List<EligibleBadgeProjection> findNearMiss(
		@Param("type") String type,
		@Param("metric") BigDecimal metric,
		@Param("userId") long userId
	);

	/**
	 * 내 뱃지 전체 목록 (docs/MSG-201.md §D2·D6) — 마스터 driven: 시딩된 뱃지는 획득 여부와 무관하게
	 * 전부 나오고, 미시딩 축은 행 자체가 없다. LEFT JOIN 의 ub.user_id = :userId equi 가
	 * user_badges PK(user_id, badge_id) prefix 를 타므로 추가 인덱스가 필요 없다. isNew 는 이 SELECT
	 * 시점의 notified_at NULL 여부 — 스탬프(markMyBadgesNotified)는 그 뒤 같은 트랜잭션에서 찍는다(§D3).
	 * 정렬은 badges.id ASC = 시딩 순 = 축별·티어 오름차순(§D6, 도감 "빈 칸이 그 자리에" UX).
	 */
	@Query(value = """
		SELECT b.id AS "badgeId", b.code, b.name, b.description, b.icon_url AS "iconUrl",
		       ub.user_id IS NOT NULL AS "earned",
		       ub.earned_at AS "earnedAt",
		       (ub.user_id IS NOT NULL AND ub.notified_at IS NULL) AS "isNew",
		       ub.featured_rank AS "featuredRank"
		FROM badges b
		LEFT JOIN user_badges ub ON ub.badge_id = b.id AND ub.user_id = :userId
		ORDER BY b.id
		""", nativeQuery = true)
	List<MyBadgeProjection> findAllWithMyStatus(@Param("userId") long userId);
}

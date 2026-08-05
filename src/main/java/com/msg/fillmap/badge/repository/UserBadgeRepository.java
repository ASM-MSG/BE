package com.msg.fillmap.badge.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.badge.entity.UserBadge;
import com.msg.fillmap.badge.entity.UserBadgeId;

/**
 * 획득 뱃지 리포지토리 (MSG-239). 지급 INSERT·대표 뱃지 교체 UPDATE 와, 뱃지 조건 판정에 쓰는 사용자별
 * 집계(metric) 쿼리 3종을 담는다. 집계가 videos·user_grids 등 남의 테이블을 읽더라도 "뱃지 판정용"이라는
 * 용도가 badge 도메인 소속이므로 각 도메인 리포지토리에 흩뿌리지 않고 여기 모은다. 상세: docs/MSG-239.md.
 */
public interface UserBadgeRepository extends JpaRepository<UserBadge, UserBadgeId> {

	/**
	 * 뱃지 지급. 동시 요청 둘이 같은 뱃지를 지급하려 해도 PK(user_id, badge_id) + ON CONFLICT DO NOTHING
	 * 이 중복 row 를 막는다 — 앱 레벨 잠금 없이 DB 가 최후 방어선. notified_at 을 바로 채우는 이유:
	 * 이 경로는 사용자 행동의 응답에 뱃지가 실려 즉시 보이므로 "미확인" 상태가 없다 (소급 지급 SQL 은
	 * 반대로 NULL 로 넣어 다음 조회 때 새 뱃지 표시 대상이 된다).
	 */
	@Modifying
	@Query(value = """
		INSERT INTO user_badges (user_id, badge_id, notified_at)
		VALUES (:userId, :badgeId, now())
		ON CONFLICT DO NOTHING
		""", nativeQuery = true)
	int insertIgnoreConflict(@Param("userId") long userId, @Param("badgeId") long badgeId);

	/**
	 * 업로드 수 뱃지(UPLOAD_COUNT)의 판정값 — 생애 업로드 수. 삭제된 영상(soft delete 행)도 일부러
	 * 포함한다: 지운 만큼 카운트가 줄면 "지웠다 다시 올리기"로 같은 뱃지 구간을 재통과하는 기형이 생기고,
	 * 한 번 딴 뱃지는 회수하지 않는다는 원칙과도 어긋난다. idx_videos_user_created prefix 를 탄다.
	 * ::numeric 캐스트는 award() 의 metric 타입(BigDecimal)에 맞추기 위함.
	 */
	@Query(value = "SELECT COUNT(*)::numeric FROM videos WHERE user_id = :userId", nativeQuery = true)
	BigDecimal countMyVideos(@Param("userId") long userId);

	/** 격자 수 뱃지(TOTAL_GRIDS)의 판정값 — 내가 수집한 격자 수. user_grids PK prefix 를 탄다. */
	@Query(value = "SELECT COUNT(*)::numeric FROM user_grids WHERE user_id = :userId", nativeQuery = true)
	BigDecimal countMyGrids(@Param("userId") long userId);

	/**
	 * 수집률 뱃지(REGION_PERCENT)의 판정값 — 이 격자가 속한 행정동의 내 수집률(%). 직접 계산하지 않고,
	 * 같은 트랜잭션에서 바로 앞서 실행된 RegionStatsCommandService.refresh(Owner A, MSG-155)가
	 * region_stats 에 저장해 둔 progress_rate 를 읽기만 한다 — 수집률 계산 로직의 단일 원천 유지, 공간
	 * 연산 0회. 행정동이 없는 격자(바다 등)는 refresh 가 아무것도 저장하지 않아 empty = 판정 건너뜀.
	 * ⚠ region_stats 는 Owner A 의 쓰기 도메인 — 이 쿼리는 읽기 전용 소비이며 스키마 변경 시 A 와 협의.
	 */
	@Query(value = """
		SELECT rs.progress_rate
		FROM region_stats rs
		JOIN grids g ON g.region_code = rs.region_code
		WHERE rs.user_id = :userId
		  AND g.grid_id = :gridId
		""", nativeQuery = true)
	Optional<BigDecimal> findMyRegionProgress(@Param("userId") long userId, @Param("gridId") String gridId);

	/**
	 * 미확인(새 뱃지) 확인 스탬프 (docs/MSG-201.md §D3) — 조회 응답에 실제로 실린 미확인 행에만
	 * notified_at 을 기록한다. IN 리스트로 좁히는 이유: user 전체 NULL UPDATE 면 SELECT 와 UPDATE 사이에
	 * 소급 시딩(V10+ 류)이 끼워 넣은 행을 노출된 적 없는데 확인 처리할 수 있다. notified_at IS NULL
	 * 가드는 동기 지급분(이미 now() 기록)·기확인분의 무의미 UPDATE 를 막는다.
	 */
	@Modifying
	@Query(value = """
		UPDATE user_badges SET notified_at = now()
		WHERE user_id = :userId AND badge_id IN (:badgeIds) AND notified_at IS NULL
		""", nativeQuery = true)
	int markMyBadgesNotified(@Param("userId") long userId, @Param("badgeIds") Collection<Long> badgeIds);

	/**
	 * 대표 뱃지 교체 직렬화용 사용자 단위 advisory 트랜잭션 잠금. 같은 사용자의 교체 요청 둘이 겹치면
	 * "전부 해제 → 다시 지정" 사이에 끼어들어 rank UNIQUE 위반(500)이 날 수 있어 사용자 단위로 줄 세운다.
	 * region_stats 갱신이 쓰는 것과 같은 패턴이며, 키를 'badge_featured:' 접두로 분리해 서로 다른 용도의
	 * 잠금이 충돌하지 않게 한다. 트랜잭션이 끝나면 자동 해제된다.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended('badge_featured:' || :userId, 0))",
		nativeQuery = true)
	Object acquireFeaturedLock(@Param("userId") long userId);

	/**
	 * 임박 알림 하루 1건 직렬화용 사용자 단위 advisory 트랜잭션 잠금 (docs/MSG-314.md D3). notifications 의
	 * 유니크 제약은 (user_id, event_key) 하나뿐이라 "오늘 기록 존재 확인 → 기록"이 select-then-act 로 남는다 —
	 * 동시 업로드 둘이 서로 다른 뱃지로 임박하면 이 잠금이 줄을 세워 후행이 선행의 커밋을 본다.
	 * acquireFeaturedLock 과 같은 패턴이며, 키를 'badge_near:' 접두로 분리해 서로 다른 용도의 잠금이
	 * 충돌하지 않게 한다. 트랜잭션이 끝나면 자동 해제된다.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended('badge_near:' || :userId, 0))",
		nativeQuery = true)
	Object acquireBadgeNearLock(@Param("userId") long userId);

	/** 대표 뱃지 전부 해제 — 교체의 1단계. IS NOT NULL 가드로 NULL→NULL 무의미 UPDATE 를 만들지 않는다. */
	@Modifying
	@Query(value = """
		UPDATE user_badges SET featured_rank = NULL
		WHERE user_id = :userId AND featured_rank IS NOT NULL
		""", nativeQuery = true)
	int clearFeatured(@Param("userId") long userId);

	/**
	 * 대표 뱃지 지정 — 교체의 2단계. WHERE 가 내 획득 row 로 한정되므로 보유 검증을 겸한다:
	 * affected 0 = 획득한 적 없는(또는 존재하지 않는) 뱃지 → 서비스가 7403 을 던져 전체 롤백.
	 */
	@Modifying
	@Query(value = """
		UPDATE user_badges SET featured_rank = :rank
		WHERE user_id = :userId AND badge_id = :badgeId
		""", nativeQuery = true)
	int setFeaturedRank(@Param("userId") long userId, @Param("badgeId") long badgeId, @Param("rank") int rank);

	/** 적용된 대표 뱃지 조회 (rank 순) — 교체 응답용. */
	@Query(value = """
		SELECT ub.badge_id AS "badgeId", b.code, b.name, b.icon_url AS "iconUrl", ub.featured_rank AS "rank"
		FROM user_badges ub
		JOIN badges b ON b.id = ub.badge_id
		WHERE ub.user_id = :userId AND ub.featured_rank IS NOT NULL
		ORDER BY ub.featured_rank
		""", nativeQuery = true)
	List<FeaturedBadgeProjection> findFeatured(@Param("userId") long userId);
}

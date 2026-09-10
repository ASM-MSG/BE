package com.msg.fillmap.user.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.user.dto.BlockedUserResponseDto;
import com.msg.fillmap.user.entity.UserBlock;
import com.msg.fillmap.user.entity.UserBlockId;

public interface UserBlockRepository extends JpaRepository<UserBlock, UserBlockId> {

	/**
	 * 멱등 차단 저장 (MSG-569 D-2). 유니크 위반을 예외로 흡수하면 PostgreSQL 이 그 트랜잭션을 abort 상태로
	 * 만들어 뒤따르는 친구 관계 삭제(D-3)가 같은 트랜잭션에서 실패한다 — ON CONFLICT DO NOTHING 은 늦은 쪽이
	 * 0행으로 성공해 "차단과 친구 삭제는 한 트랜잭션"(FR-MOD-16)이 지켜진다. 재차단은 0행이라 created_at 이
	 * 최초 값으로 남는다(AC-02). 선례: VideoRepository.upsertGrid·upsertUserGrid.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO user_blocks (blocker_id, blocked_id, created_at)
		VALUES (:blockerId, :blockedId, :createdAt)
		ON CONFLICT (blocker_id, blocked_id) DO NOTHING
		""", nativeQuery = true)
	int insertIgnore(@Param("blockerId") Long blockerId, @Param("blockedId") Long blockedId,
		@Param("createdAt") LocalDateTime createdAt);

	/**
	 * 해제 (MSG-569 D-3) — 자기 방향 한 행만 지우는 벌크 DELETE 한 문장. deleteById 는 SELECT 뒤 DELETE 라
	 * 동시 해제에서 진 쪽이 StaleStateException 500 이 되지만, 이 문장은 0행도 그냥 성공이다(멱등, AC-03).
	 * 역방향(B→A) 행은 건드리지 않아 상호 비노출이 그대로 유지된다(AC-18).
	 */
	@Modifying
	@Query("DELETE FROM UserBlock b WHERE b.id.blockerId = :a AND b.id.blockedId = :b")
	int deletePair(@Param("a") Long a, @Param("b") Long b);

	/**
	 * 내가 걸은 차단 목록 (MSG-569 FR-4) — 연관 경로 생성자 프로젝션이라 users 조인은 FK 로 한 번 잇는다.
	 * 정렬 타이브레이크 blocked.id 는 PK 두 번째 컬럼이라 같은 시각에도 순서가 결정적이다.
	 */
	@Query("""
		SELECT new com.msg.fillmap.user.dto.BlockedUserResponseDto(
			b.blocked.id, b.blocked.nickname, b.blocked.profileImageUrl, b.createdAt)
		FROM UserBlock b
		WHERE b.blocker.id = :userId
		ORDER BY b.createdAt DESC, b.blocked.id DESC
		""")
	List<BlockedUserResponseDto> findBlockedUsers(@Param("userId") Long userId);

	/**
	 * 방향 무관 차단 존재 판정 (MSG-569 D-5) — 단건 경로(재생·행사 상세·행사 상호작용 서두)가 쓴다.
	 * b.blocker.id 는 FK 컬럼 직접 참조라 조인이 없고, 두 방향이 각각 PK 와 idx_user_blocks_blocked 단건
	 * lookup 이다(FriendshipRepository.existsAcceptedPair 와 같은 형태).
	 */
	@Query("""
		SELECT COUNT(b) > 0 FROM UserBlock b
		WHERE (b.blocker.id = :a AND b.blocked.id = :b)
			OR (b.blocker.id = :b AND b.blocked.id = :a)
		""")
	boolean existsEitherWay(@Param("a") Long a, @Param("b") Long b);
}

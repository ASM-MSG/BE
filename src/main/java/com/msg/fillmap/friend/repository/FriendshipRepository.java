package com.msg.fillmap.friend.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.friend.dto.FriendListItemResponseDto;
import com.msg.fillmap.friend.dto.ReceivedFriendRequestResponseDto;
import com.msg.fillmap.friend.entity.Friendship;
import com.msg.fillmap.friend.entity.FriendshipId;

public interface FriendshipRepository extends JpaRepository<Friendship, FriendshipId> {

	/**
	 * 방향 무관 쌍 조회 — "동일 쌍 최대 1행" 불변식(§D3) 하에서 결과는 0~1행이다.
	 * FOR UPDATE 행 잠금 — 자동 수락(더티 체킹 UPDATE)·삭제가 동시 수락/거절과 직렬화된다
	 * (Codex 리뷰 반영). 호출처 둘 다 쓰기 트랜잭션(request·deleteFriend)이라 잠금 비용은 행 1개.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		SELECT f FROM Friendship f
		WHERE (f.id.requesterId = :a AND f.id.addresseeId = :b)
			OR (f.id.requesterId = :b AND f.id.addresseeId = :a)
		""")
	Optional<Friendship> findPair(@Param("a") Long a, @Param("b") Long b);

	/** 수락·거절용 복합 PK 행 잠금 조회 — 같은 요청의 동시 수락+거절이 둘 다 성공하는 경합 차단 (Codex 리뷰 반영). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Friendship> findWithLockById(FriendshipId id);

	/**
	 * 받은 대기 요청 목록 — 보낸 사람 정보 포함, 최신순 (FR-9). User 를 FK 매핑 없이 Long 조인하는
	 * 생성자 프로젝션. WHERE 는 V1 인덱스 idx_friendships_addressee(addressee_id, status)가 커버한다.
	 */
	@Query("""
		SELECT new com.msg.fillmap.friend.dto.ReceivedFriendRequestResponseDto(
			f.id.requesterId, u.nickname, u.profileImageUrl, f.createdAt)
		FROM Friendship f, User u
		WHERE u.id = f.id.requesterId
			AND f.id.addresseeId = :userId
			AND f.status = com.msg.fillmap.friend.entity.FriendshipStatus.PENDING
		ORDER BY f.createdAt DESC
		""")
	List<ReceivedFriendRequestResponseDto> findReceivedRequests(@Param("userId") Long userId);

	/**
	 * 친구 목록 — 수락 시각 내림차순 (MSG-186 FR-1·2 기본 정렬). ACCEPTED 행 1개가 양방향 관계이므로
	 * "내가 requester 인 행의 addressee + 내가 addressee 인 행의 requester" 합집합을 OR 2분기 세타 조인
	 * 1방으로 낸다(SQL UNION 아님). requester 방향은 PK(requester_id, addressee_id), addressee 방향은
	 * V1 idx_friendships_addressee(addressee_id, status)가 커버한다. responded_at 은 nullable 컬럼이라
	 * NULLS LAST + u.id 타이브레이크로 방어한다 (ACCEPTED 행은 accept() 가 항상 기록하므로 사실상 non-null).
	 */
	@Query("""
		SELECT new com.msg.fillmap.friend.dto.FriendListItemResponseDto(
			u.id, u.nickname, u.profileImageUrl, u.gridColor)
		FROM Friendship f, User u
		WHERE f.status = com.msg.fillmap.friend.entity.FriendshipStatus.ACCEPTED
			AND ((f.id.requesterId = :userId AND u.id = f.id.addresseeId)
				OR (f.id.addresseeId = :userId AND u.id = f.id.requesterId))
		ORDER BY f.respondedAt DESC NULLS LAST, u.id ASC
		""")
	List<FriendListItemResponseDto> findFriendsOrderByAcceptedAt(@Param("userId") Long userId);

	/**
	 * 친구 목록 — 닉네임순 (MSG-186 FR-2 전환). 위 쿼리와 ORDER BY 만 다르다 — 세타 조인 생성자
	 * 프로젝션에는 Spring Data Sort 동적 적용이 맞지 않아 정적 2본으로 둔다. 닉네임 중복이 허용되므로
	 * (MSG-203) u.id 타이브레이크가 필수다.
	 */
	@Query("""
		SELECT new com.msg.fillmap.friend.dto.FriendListItemResponseDto(
			u.id, u.nickname, u.profileImageUrl, u.gridColor)
		FROM Friendship f, User u
		WHERE f.status = com.msg.fillmap.friend.entity.FriendshipStatus.ACCEPTED
			AND ((f.id.requesterId = :userId AND u.id = f.id.addresseeId)
				OR (f.id.addresseeId = :userId AND u.id = f.id.requesterId))
		ORDER BY u.nickname ASC, u.id ASC
		""")
	List<FriendListItemResponseDto> findFriendsOrderByNickname(@Param("userId") Long userId);

	/**
	 * 방향 무관 ACCEPTED 관계 존재 확인 — 무잠금 (MSG-186 D4). findPair 는 PESSIMISTIC_WRITE 라
	 * readOnly 트랜잭션에서 쓸 수 없어(PostgreSQL 이 read-only 에서 FOR UPDATE 계열을 거부) 조회 전용으로
	 * 분리했다. 판정은 요청 시점 실시간 — 친구 삭제(행 DELETE) 즉시 false 로 수렴한다 (FR-8).
	 */
	@Query("""
		SELECT COUNT(f) > 0 FROM Friendship f
		WHERE f.status = com.msg.fillmap.friend.entity.FriendshipStatus.ACCEPTED
			AND ((f.id.requesterId = :a AND f.id.addresseeId = :b)
				OR (f.id.requesterId = :b AND f.id.addresseeId = :a))
		""")
	boolean existsAcceptedPair(@Param("a") Long a, @Param("b") Long b);
}

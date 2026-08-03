package com.msg.fillmap.friend.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}

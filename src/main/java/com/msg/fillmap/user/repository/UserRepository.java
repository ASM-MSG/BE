package com.msg.fillmap.user.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmail(String email);

	Optional<User> findByEmail(String email);

	Optional<User> findByProviderAndOid(AuthProvider provider, String oid);

	/** 친구 코드로 상대 특정 (MSG-185) — friend 도메인의 미리보기·요청이 소비한다. */
	Optional<User> findByFriendCode(String friendCode);

	/** 계정 삭제용 S3 키 수집 — videos 를 읽지만 용도가 user 소속 (UserBadgeRepository 관례). 4컬럼 모두 실제 S3 key. */
	@Query(value = """
		SELECT original_s3_key, encoded_url, thumbnail_url, blurred_s3_key
		FROM videos
		WHERE user_id = :userId
		""", nativeQuery = true)
	List<Object[]> findAllS3KeysByUserId(@Param("userId") Long userId);

	/** 삭제 행 수 반환 — 0 이면 이미 없는 유저(1404). deleteById 는 부재 시 조용히 무시라 판별 불가. */
	@Modifying
	@Query("DELETE FROM User u WHERE u.id = :userId")
	int deleteUser(@Param("userId") Long userId);

	/**
	 * users 행 KEY SHARE 선취 (MSG-313 Codex 2R) — notifications FK 검사가 나중에 잡을 잠금을 videos 행
	 * 잠금보다 먼저 확보해, 탈퇴 CASCADE(users 배타 → videos)와 잠금 순서를 통일한다. 빈 결과 = 탈퇴
	 * 진행/완료라 호출자가 전이·알림을 스킵한다.
	 */
	@Query(value = "SELECT id FROM users WHERE id = :userId FOR KEY SHARE", nativeQuery = true)
	Optional<Long> findIdForKeyShare(@Param("userId") Long userId);
}

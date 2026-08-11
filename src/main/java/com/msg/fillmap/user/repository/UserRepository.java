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

	/**
	 * OAuth 첫 로그인 가입 (Codex 지적 후속) — 동시 요청이 겹쳐도 UNIQUE 위반 없이 한쪽만 삽입된다.
	 * JPA save 는 위반이 트랜잭션 abort 로 번져 같은 트랜잭션에서 승자 재조회가 불가하므로 ON CONFLICT
	 * 무삽입으로 내린다(badge insertIgnoreConflict 선례). 대상 미지정 ON CONFLICT 라 provider+oid 충돌과
	 * email 충돌 모두 0행 반환 — 어느 쪽인지는 호출자가 oid 재조회로 가른다. friend_code 는 NOT NULL 에
	 * DB DEFAULT 가 없어(V18) 호출자가 엔티티 팩토리 생성값을 넘긴다 — ~1e-9 확률의 코드 충돌도 0행이
	 * 되는 건 기존 500 이 1409 로 바뀌는 것뿐이라 재시도 없이 수용(User.generateFriendCode 주석 동일).
	 * 그 외 미지정 컬럼(role·grid_color·created_at 등)은 스키마 DEFAULT 를 따르며 createOAuthUser 가
	 * 넣던 값과 같다.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO users (provider, oid, email, nickname, friend_code)
		VALUES (:provider, :oid, :email, :nickname, :friendCode)
		ON CONFLICT DO NOTHING
		""", nativeQuery = true)
	int insertOAuthUserIgnoreConflict(@Param("provider") String provider, @Param("oid") String oid,
		@Param("email") String email, @Param("nickname") String nickname,
		@Param("friendCode") String friendCode);

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

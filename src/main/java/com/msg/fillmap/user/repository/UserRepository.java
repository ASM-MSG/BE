package com.msg.fillmap.user.repository;

import java.time.LocalDateTime;
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

	/**
	 * 계정 삭제용 프로필 이미지 URL 수집 (MSG-373). 엔티티를 안 띄우는 스칼라 조회다 — deleteUser 는
	 * 벌크 JPQL DELETE 라 영속성 컨텍스트에 남은 User 가 삭제된 행을 가리키는 상태를 만들지 않는다.
	 * 컬럼이 null(미설정)이면 빈 Optional 이라 호출부에 별도 분기가 없다.
	 */
	@Query("SELECT u.profileImageUrl FROM User u WHERE u.id = :userId")
	Optional<String> findProfileImageUrlById(@Param("userId") Long userId);

	/** 삭제 행 수 반환 — 0 이면 이미 없는 유저(1404). deleteById 는 부재 시 조용히 무시라 판별 불가. */
	@Modifying
	@Query("DELETE FROM User u WHERE u.id = :userId")
	int deleteUser(@Param("userId") Long userId);

	/**
	 * 위치정보 사용 동의 갱신 (MSG-402 §D-4) — 읽기·비교·쓰기를 UPDATE 한 문장에 담는다. 조회 후 값을
	 * 비교하는 방식은 동시 PUT 에서 두 요청이 같은 이전 값을 읽어 변경 시각이 두 번 갱신되거나 상태가
	 * 역전될 수 있는데, 한 문장이면 DB 행 잠금이 두 요청을 직렬화한다. CASE 가 같은 값 재저장 시 기존
	 * 시각을 그대로 두므로 멱등(FR-4)이 문장 수준에서 성립한다 — 서비스에 분기 코드가 없다.
	 * NULL 비교가 성립해야 하므로 `=` 가 아니라 PostgreSQL 의 IS DISTINCT FROM 을 쓴다.
	 * 반환은 영향 행 수 — 0 이면 이미 없는 사용자라 호출자가 1404 로 바꾼다.
	 * clearAutomatically 는 이 UPDATE 를 못 본 1차 캐시 스냅숏을 비워 뒤이은 재조회가 DB 를 읽게 한다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
		UPDATE users
		SET location_consent = :consented,
		    location_consent_changed_at = CASE
		        WHEN location_consent IS DISTINCT FROM :consented THEN :changedAt
		        ELSE location_consent_changed_at
		    END
		WHERE id = :userId
		""", nativeQuery = true)
	int updateLocationConsent(@Param("userId") Long userId, @Param("consented") boolean consented,
		@Param("changedAt") LocalDateTime changedAt);

	/**
	 * 가입 약관 동의 제출 (MSG-433 §D-5) — updateLocationConsent 의 확장이다. 필수 4항목은 Bean
	 * Validation 을 통과했으면 전부 동의 확정이라 파라미터는 마케팅 값과 시각뿐이다.
	 *
	 * COALESCE 가 필수 3항목의 최초 동의 시각을 보존해 재제출이 증빙 시각을 덮지 않는다(FR-4·8).
	 * 위치와 마케팅은 IS DISTINCT FROM CASE 로 값이 실제로 달라질 때만 시각을 갱신한다 — MSG-402
	 * 온보딩으로 이미 위치 동의를 켠 사용자는 기존 동의 시각이 그대로 남는다(FR-5). 위치는 철회가
	 * 불가해진 뒤로도(FR-USER-14 개정) 이 CASE 가 필요하다 — 선행 동의자의 시각 보존이 그 역할이다.
	 * 한 문장이라 "전량 저장 또는 전량 미저장"(FR-3)과 멱등(FR-8)이 문장 수준에서 성립한다.
	 * 반환은 영향 행 수 — 0 이면 이미 없는 사용자라 호출자가 1404 로 바꾼다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
		UPDATE users
		SET age_over14_consented_at = COALESCE(age_over14_consented_at, :now),
		    service_terms_consented_at = COALESCE(service_terms_consented_at, :now),
		    privacy_consented_at = COALESCE(privacy_consented_at, :now),
		    location_consent = TRUE,
		    location_consent_changed_at = CASE
		        WHEN location_consent IS DISTINCT FROM TRUE THEN :now
		        ELSE location_consent_changed_at
		    END,
		    marketing_consent = :marketing,
		    marketing_consent_changed_at = CASE
		        WHEN marketing_consent IS DISTINCT FROM :marketing THEN :now
		        ELSE marketing_consent_changed_at
		    END
		WHERE id = :userId
		""", nativeQuery = true)
	int submitConsents(@Param("userId") Long userId, @Param("marketing") boolean marketing,
		@Param("now") LocalDateTime now);

	/** 마케팅 수신 동의 갱신 (MSG-433 §D-4) — updateLocationConsent 와 완전 동형이다. */
	@Modifying(clearAutomatically = true)
	@Query(value = """
		UPDATE users
		SET marketing_consent = :consented,
		    marketing_consent_changed_at = CASE
		        WHEN marketing_consent IS DISTINCT FROM :consented THEN :changedAt
		        ELSE marketing_consent_changed_at
		    END
		WHERE id = :userId
		""", nativeQuery = true)
	int updateMarketingConsent(@Param("userId") Long userId, @Param("consented") boolean consented,
		@Param("changedAt") LocalDateTime changedAt);

	/**
	 * users 행 KEY SHARE 선취 (MSG-313 Codex 2R) — notifications FK 검사가 나중에 잡을 잠금을 videos 행
	 * 잠금보다 먼저 확보해, 탈퇴 CASCADE(users 배타 → videos)와 잠금 순서를 통일한다. 빈 결과 = 탈퇴
	 * 진행/완료라 호출자가 전이·알림을 스킵한다.
	 */
	@Query(value = "SELECT id FROM users WHERE id = :userId FOR KEY SHARE", nativeQuery = true)
	Optional<Long> findIdForKeyShare(@Param("userId") Long userId);
}

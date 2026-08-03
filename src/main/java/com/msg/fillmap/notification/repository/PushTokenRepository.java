package com.msg.fillmap.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.notification.entity.PushToken;

/**
 * 푸시 토큰 리포지토리 (MSG-178). 쓰기는 native 2종(UPSERT·DELETE)뿐 — fcm_token 이 PK 라
 * INSERT 로 짜면 재로그인 시 PK 충돌이라 ON CONFLICT DO UPDATE 로 흡수한다 (ZoneRepository.upsert 선례).
 */
public interface PushTokenRepository extends JpaRepository<PushToken, String> {

	/**
	 * 토큰 등록/갱신 UPSERT (FR-1). 같은 fcm_token 재등록 시 user_id 포함 전 필드를 EXCLUDED 로
	 * 갱신한다 — 같은 디바이스 토큰이 항상 마지막 등록 계정 소유가 되는 게 의도된 시맨틱(계정 전환 이관).
	 */
	@Modifying
	@Query(value = """
		INSERT INTO push_tokens (fcm_token, user_id, platform, app_version, last_used_at)
		VALUES (:fcmToken, :userId, :platform, :appVersion, now())
		ON CONFLICT (fcm_token) DO UPDATE SET
			user_id      = EXCLUDED.user_id,
			platform     = EXCLUDED.platform,
			app_version  = EXCLUDED.app_version,
			last_used_at = EXCLUDED.last_used_at
		""", nativeQuery = true)
	int upsert(
		@Param("fcmToken") String fcmToken,
		@Param("userId") long userId,
		@Param("platform") String platform,
		@Param("appVersion") String appVersion
	);

	/**
	 * 토큰 해제 (FR-2). 소유 검증(user_id 대조) 포함 — 이관 경합(토큰이 다른 계정으로 넘어간 직후
	 * 지연 도착한 이전 사용자의 DELETE)이 새 사용자 행을 오삭제하는 것을 차단한다 (Codex 1R P2).
	 * affected 0이어도 성공 — 없는 토큰·소유 불일치 모두 0행 = 멱등, 반환 int 는 무시.
	 */
	@Modifying
	@Query(value = """
		DELETE FROM push_tokens WHERE fcm_token = :fcmToken AND user_id = :userId
		""", nativeQuery = true)
	int deleteByTokenAndUser(@Param("fcmToken") String fcmToken, @Param("userId") long userId);
}

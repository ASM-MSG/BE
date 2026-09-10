package com.msg.fillmap.notification.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.notification.entity.NotificationOptOut;
import com.msg.fillmap.notification.entity.NotificationOptOutId;

/**
 * 알림 수신 거부 리포지토리 (MSG-180). 쓰기는 native 2종(INSERT ON CONFLICT·DELETE)뿐 — 둘 다
 * 1문장 멱등이라 같은 값 재전환이 오류 없이 같은 상태로 끝난다 (PushTokenRepository 선례).
 * isEnabled 판정은 기본 제공 existsById(PK 단건 히트) — 추가 인덱스 없음(PK가 user_id 선두).
 */
public interface NotificationOptOutRepository extends JpaRepository<NotificationOptOut, NotificationOptOutId> {

	/** off 저장 (FR-7). 이미 off 인 카테고리 재저장은 DO NOTHING 0행 = 멱등, 반환 int 는 무시. */
	@Modifying
	@Query(value = """
		INSERT INTO notification_opt_outs (user_id, category)
		VALUES (:userId, :category)
		ON CONFLICT (user_id, category) DO NOTHING
		""", nativeQuery = true)
	int insertOptOut(@Param("userId") long userId, @Param("category") String category);

	/** on 복귀 (FR-7) — off 행 삭제. 이미 on(행 부재)이어도 0행 = 멱등. */
	@Modifying
	@Query(value = """
		DELETE FROM notification_opt_outs WHERE user_id = :userId AND category = :category
		""", nativeQuery = true)
	int deleteOptOut(@Param("userId") long userId, @Param("category") String category);

	/** 사용자 off 행 전체 조회 (설정 화면 GET) — PK 선두 user_id 사용, 최대 3행. */
	List<NotificationOptOut> findAllByIdUserId(Long userId);
}

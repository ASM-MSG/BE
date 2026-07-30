package com.msg.fillmap.mission.repository;

import java.math.BigDecimal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.mission.entity.UserMission;
import com.msg.fillmap.mission.entity.UserMissionId;

/**
 * 스탬프 리포지토리 (MSG-223). 발급 INSERT 와 MISSION_COUNT 뱃지 판정용 집계(metric)를 담는다 —
 * UserBadgeRepository 의 지급·집계 배치 패턴 미러.
 */
public interface UserMissionRepository extends JpaRepository<UserMission, UserMissionId> {

	/**
	 * 스탬프 발급 (§D2 3단계). 동시 업로드 둘이 같은 미션을 발급하려 해도 PK(user_id, mission_id) +
	 * ON CONFLICT DO NOTHING 이 중복 row 를 막는다(FR-14) — 앱 레벨 잠금 없이 DB 가 최후 방어선.
	 * 영향 행 1일 때만 "이 요청으로 새로 획득한" 스탬프다. completed_at 은 컬럼 DEFAULT.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO user_missions (user_id, mission_id)
		VALUES (:userId, :missionId)
		ON CONFLICT DO NOTHING
		""", nativeQuery = true)
	int insertIgnoreConflict(@Param("userId") long userId, @Param("missionId") long missionId);

	/**
	 * 미션 뱃지(MISSION_COUNT)의 판정값 — 내 스탬프 총수(FR-17). 비회수(FR-15)라 단조 증가 —
	 * 뱃지 비회수 원칙과 정합. user_missions PK prefix 를 탄다. ::numeric 캐스트는 award() 의
	 * metric 타입(BigDecimal)에 맞추기 위함(countMyVideos 선례).
	 */
	@Query(value = "SELECT COUNT(*)::numeric FROM user_missions WHERE user_id = :userId", nativeQuery = true)
	BigDecimal countMyStamps(@Param("userId") long userId);
}

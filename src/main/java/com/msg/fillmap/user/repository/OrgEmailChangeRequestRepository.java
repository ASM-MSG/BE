package com.msg.fillmap.user.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.user.entity.OrgEmailChangeRequest;

public interface OrgEmailChangeRequestRepository extends JpaRepository<OrgEmailChangeRequest, Long> {

	/**
	 * 아이디 변경 요청 접수 (MSG-497 §6). 본인 PENDING 행이 있으면 요청 이메일과 접수 시각을 갱신하고,
	 * 없으면 새 행을 만든다 — 마지막 요청이 유효하다(오타 정정 재요청 허용).
	 *
	 * <p>조회 후 INSERT 의 두 문장으로 가르지 않는 이유: 동시 요청 두 개가 부분 유니크 인덱스
	 * (uq_org_email_change_requests_pending) 위반으로 500 이 된다. PostgreSQL 전용 ON CONFLICT 라
	 * 컨벤션의 native 허용 대상이고, 부분 인덱스가 대상이라 추론 절에 같은 WHERE 를 적는다.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO org_email_change_requests (user_id, requested_email, status, created_at)
		VALUES (:userId, :email, 'PENDING', :now)
		ON CONFLICT (user_id) WHERE status = 'PENDING'
		DO UPDATE SET requested_email = EXCLUDED.requested_email, created_at = EXCLUDED.created_at
		""", nativeQuery = true)
	int upsertPending(@Param("userId") Long userId, @Param("email") String email,
		@Param("now") LocalDateTime now);

	/** 관리자 큐(MSG-499)의 발판이자 접수 결과 검증축 — 사용자당 PENDING 은 부분 유니크 인덱스가 1건으로 강제한다. */
	List<OrgEmailChangeRequest> findAllByUserId(Long userId);
}

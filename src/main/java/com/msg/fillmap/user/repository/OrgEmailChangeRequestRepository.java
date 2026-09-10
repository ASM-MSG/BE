package com.msg.fillmap.user.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.user.dto.AdminEmailChangeRequestItemResponseDto;
import com.msg.fillmap.user.entity.OrgEmailChangeRequest;
import com.msg.fillmap.user.entity.OrgEmailChangeStatus;

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

	/**
	 * 관리자 큐 (MSG-500 §API 7) — 상태 필터 기준 접수 최신순이다. 계정의 기관명·현재 이메일이 심사 재료라
	 * users 세타 조인 + 생성자 프로젝션이다(요청 엔티티가 userId 를 연관 없이 보관하는 기존 형태를 그대로 둔다).
	 */
	@Query(value = """
		SELECT new com.msg.fillmap.user.dto.AdminEmailChangeRequestItemResponseDto(
			r.id, r.userId, u.orgName, u.email, r.requestedEmail, r.status,
			r.createdAt, r.processedAt, r.rejectReason)
		FROM OrgEmailChangeRequest r, User u
		WHERE u.id = r.userId AND r.status = :status
		ORDER BY r.createdAt DESC, r.id DESC
		""",
		countQuery = "SELECT COUNT(r) FROM OrgEmailChangeRequest r WHERE r.status = :status")
	Page<AdminEmailChangeRequestItemResponseDto> findAdminPageByStatus(
		@Param("status") OrgEmailChangeStatus status, Pageable pageable);

	/** 탭 뱃지용 상태별 전체 건수 — 필터와 무관하다 (MSG-499 요청 큐 선례). */
	long countByStatus(OrgEmailChangeStatus status);

	/**
	 * 승인 전이 (MSG-500 D-13 검토 시점 가드). 술어에 <b>접수 시각까지</b> 넣어 상태와 내용을 원자로 비교한다 —
	 * 접수 UPSERT 가 재제출을 같은 PENDING 행의 제자리 갱신으로 처리하므로, 상태만 보면 관리자가 큐를 띄운 뒤
	 * 재제출된 <b>본 적 없는 이메일</b>을 승인하게 된다. 영향 행이 0이면 호출자가 재조회로 가른다:
	 * 없으면 1427, PENDING 이 아니면 1428, PENDING 인데 시각이 다르면 1429 다.
	 * <p>
	 * {@code flushAutomatically} 는 걸지 않는다 — 이 앞에 엔티티 수정이 없고(읽기뿐), 뒤따르는
	 * users.email 교체도 벌크 UPDATE 라 두 문장의 순서가 코드 순서 그대로다. 앞쪽에 엔티티 수정이 생기면
	 * 그 변경이 flush 되지 않은 채 이 UPDATE 가 먼저 나가므로 그때는 플래그가 필요하다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE OrgEmailChangeRequest r
		SET r.status = com.msg.fillmap.user.entity.OrgEmailChangeStatus.APPROVED, r.processedAt = :now
		WHERE r.id = :id
			AND r.status = com.msg.fillmap.user.entity.OrgEmailChangeStatus.PENDING
			AND r.createdAt = :requestedAt
		""")
	int approvePending(@Param("id") Long id, @Param("requestedAt") LocalDateTime requestedAt,
		@Param("now") LocalDateTime now);

	/** 반려 전이 — 승인과 같은 검토 시점 가드다(재제출로 내용이 바뀐 요청을 낡은 사유로 반려하지 않는다). */
	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE OrgEmailChangeRequest r
		SET r.status = com.msg.fillmap.user.entity.OrgEmailChangeStatus.REJECTED,
			r.processedAt = :now, r.rejectReason = :reason
		WHERE r.id = :id
			AND r.status = com.msg.fillmap.user.entity.OrgEmailChangeStatus.PENDING
			AND r.createdAt = :requestedAt
		""")
	int rejectPending(@Param("id") Long id, @Param("requestedAt") LocalDateTime requestedAt,
		@Param("reason") String reason, @Param("now") LocalDateTime now);
}

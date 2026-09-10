package com.msg.fillmap.user.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.user.entity.OrgAccountRequest;
import com.msg.fillmap.user.entity.OrgAccountRequestStatus;

public interface OrgAccountRequestRepository extends JpaRepository<OrgAccountRequest, Long> {

	/**
	 * 계정 발급 요청 접수 (MSG-499 API 1). 같은 이메일의 PENDING 행이 있으면 내용과 마지막 접수 시각을
	 * 갱신하고, 없으면 새 행을 만든다 — 마지막 접수가 유효하다(더블클릭 재제출과 오타 정정이 한 행으로
	 * 수렴). created_at 은 갱신 대상이 아니라 최초 접수 시각이 보존된다.
	 *
	 * <p>조회 후 INSERT 두 문장으로 가르지 않는 이유는 OrgEmailChangeRequestRepository.upsertPending 과
	 * 같다 — 동시 접수 두 건이 부분 유니크 인덱스 위반으로 500 이 된다. PostgreSQL 전용 ON CONFLICT 라
	 * 컨벤션의 native 허용 대상이고, 부분 인덱스가 대상이라 추론 절에 같은 WHERE 를 적는다.
	 * 발급됨·반려 상태의 이메일은 인덱스 밖이라 새 대기 행이 만들어진다(재신청 경로).
	 */
	@Modifying
	@Query(value = """
		INSERT INTO org_account_requests
		    (org_name, contact_name, contact_phone, email, event_name, content, status,
		     created_at, updated_at)
		VALUES (:orgName, :contactName, :contactPhone, :email, :eventName, :content, 'PENDING',
		     :now, :now)
		ON CONFLICT (email) WHERE status = 'PENDING'
		DO UPDATE SET org_name = EXCLUDED.org_name, contact_name = EXCLUDED.contact_name,
		    contact_phone = EXCLUDED.contact_phone, event_name = EXCLUDED.event_name,
		    content = EXCLUDED.content, updated_at = EXCLUDED.updated_at
		""", nativeQuery = true)
	int upsertPending(@Param("orgName") String orgName, @Param("contactName") String contactName,
		@Param("contactPhone") String contactPhone, @Param("email") String email,
		@Param("eventName") String eventName, @Param("content") String content,
		@Param("now") LocalDateTime now);

	/**
	 * 심사 진입부의 행 잠금 조회 (승인·반려 공통). 동시 처리의 늦은 쪽은 잠금 대기 후 바뀐 상태를 읽고
	 * 1422 로 걸린다 (AdminReportService.lockPendingReport 선례).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<OrgAccountRequest> findWithLockById(Long id);

	/** 큐 목록 — 마지막 접수 최신순 고정 (idx_org_account_requests_status_updated 가 받는다). */
	Page<OrgAccountRequest> findAllByStatusOrderByUpdatedAtDesc(OrgAccountRequestStatus status, Pageable pageable);

	/** 탭 뱃지 건수 — 상태 필터와 무관한 전체 집계라 상태별로 세 번 부른다(관리자 저빈도 흐름). */
	long countByStatus(OrgAccountRequestStatus status);
}

package com.msg.fillmap.event.submission.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.event.submission.dto.AdminApprovedEventItemResponseDto;
import com.msg.fillmap.event.submission.dto.AdminEventSubmissionItemResponseDto;
import com.msg.fillmap.event.submission.entity.EventSubmission;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatus;

public interface EventSubmissionRepository extends JpaRepository<EventSubmission, Long> {

	/**
	 * 소유 조회 (FR-14). 조회를 항상 id + userId 쌍으로 하는 것이 존재 은닉의 구현이다 — 없는 신청과 남의
	 * 신청이 같은 빈 결과가 되어 응답이 갈릴 코드 경로 자체가 없다.
	 */
	@EntityGraph(attributePaths = "locations")
	Optional<EventSubmission> findByIdAndUserId(Long id, Long userId);

	/**
	 * 내 신청 목록 (FR-11). 페이지네이션 없음 — 내부 소수 사용자다. id 는 같은 시각 제출의 결정성 보험이다.
	 * 상태별 건수도 이 결과에서 센다 — 목록이 곧 전량이라 GROUP BY 를 따로 날리면 스냅숏만 갈라진다.
	 */
	List<EventSubmission> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

	/**
	 * 반려본 재제출의 상태 복귀 (FR-13) — 검사와 갱신이 한 문장이라 동시 PATCH 두 건이 둘 다 REJECTED 를
	 * 관찰하고 둘 다 성공하는 경합이 성립하지 않는다. 술어에 userId 가 들어 있어 남의 행은 어떤 경로로도
	 * 수정되지 않는다(뒤의 재로드나 롤백에 기대지 않는다).
	 * <p>
	 * 영향 행이 0이면 호출자가 소유 조회로 분기한다 — 행이 없으면 13430, 있는데 REJECTED 가 아니면 13434 다.
	 * id + status 로만 걸면 남의 REJECTED 행이 13434 로 새어 존재가 드러나므로 분기 기준은 반드시 소유 조회다.
	 * {@code clearAutomatically} 는 벌크 UPDATE 가 우회한 영속성 컨텍스트의 스테일 스냅숏을 비운다 —
	 * 호출자의 재로드가 DB 의 새 상태를 읽어야 하기 때문이다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE EventSubmission s
		SET s.status = com.msg.fillmap.event.submission.entity.EventSubmissionStatus.IN_REVIEW,
			s.updatedAt = :now
		WHERE s.id = :id AND s.userId = :userId
			AND s.status = com.msg.fillmap.event.submission.entity.EventSubmissionStatus.REJECTED
		""")
	int reopenRejected(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

	/**
	 * 관리자 심사 큐 (MSG-500 §API 1). 신청자의 기관명(users.org_name)이 목록 재료라 세타 조인 + 생성자
	 * 프로젝션이다 — EventSubmission 은 userId 를 연관 없이 보관하는 기존 엔티티라 컨벤션의 혼재 허용 조항을
	 * 따른다(소급 리팩터링 금지). {@code size(s.locations)} 는 위치 수를 상관 서브쿼리 한 번으로 세어,
	 * 항목마다 위치를 다시 읽는 N+1 을 만들지 않는다.
	 * <p>
	 * 정렬은 접수 최신순이고 id 는 같은 시각 접수의 결정성 보험이다(내 신청 목록과 같은 규칙).
	 * countQuery 를 따로 주는 것은 프로젝션·조인이 붙은 자동 생성 count 가 부정확해질 여지를 없애기 위해서다.
	 */
	@Query(value = """
		SELECT new com.msg.fillmap.event.submission.dto.AdminEventSubmissionItemResponseDto(
			s.id, s.submissionNo, s.type, s.status, s.title, s.organizerName, u.orgName,
			s.startsOn, s.endsOn, size(s.locations), s.createdAt, s.updatedAt)
		FROM EventSubmission s, User u
		WHERE u.id = s.userId AND s.status = :status
		ORDER BY s.createdAt DESC, s.id DESC
		""",
		countQuery = "SELECT COUNT(s) FROM EventSubmission s WHERE s.status = :status")
	Page<AdminEventSubmissionItemResponseDto> findAdminPageByStatus(
		@Param("status") EventSubmissionStatus status, Pageable pageable);

	/** 탭 뱃지용 상태별 전체 건수 — 필터와 무관하다 (MSG-499 요청 큐 선례). */
	long countByStatus(EventSubmissionStatus status);

	/**
	 * 관리자 심사 상세 (MSG-500 §API 2). 소유 술어가 없다 — 관리자 조회에는 존재 은닉이 없어 없는 id 가
	 * 그대로 404 다. 위치는 상세 응답이 통째로 그리므로 EntityGraph 로 함께 든다.
	 */
	@EntityGraph(attributePaths = "locations")
	Optional<EventSubmission> findWithLocationsById(Long id);

	/**
	 * 승인 전이 (MSG-500 D-1) — 상태 확인과 갱신이 한 문장이라 동시 승인 두 건이 둘 다 IN_REVIEW 를
	 * 관찰하고 둘 다 성공하는 경합이 성립하지 않는다. 승인 번호를 같은 문장에서 채우는 것은 DDL CHECK
	 * (chk_event_sub_approval)가 "승인 행 = 승인 번호 있는 행"을 요구하기 때문이다.
	 * <p>
	 * 영향 행이 0이면 호출자가 재조회로 가른다 — 행이 없으면 13430, 있으면 13450(동시 승인의 패자 포함)이다.
	 * {@code clearAutomatically} 는 벌크 UPDATE 가 우회한 영속성 컨텍스트의 스테일 스냅숏을 비운다 —
	 * 호출자가 산출물 링크를 달기 전에 새 상태를 다시 읽어야 하기 때문이다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE EventSubmission s
		SET s.status = com.msg.fillmap.event.submission.entity.EventSubmissionStatus.APPROVED,
			s.approvalNo = :approvalNo,
			s.updatedAt = :now
		WHERE s.id = :id
			AND s.status = com.msg.fillmap.event.submission.entity.EventSubmissionStatus.IN_REVIEW
		""")
	int approveInReview(@Param("id") Long id, @Param("approvalNo") String approvalNo,
		@Param("now") LocalDateTime now);

	/** 반려 전이 (MSG-500 §반려) — 승인과 같은 원자 전이다. 사유는 신청 행이 아니라 이력 행에 쌓인다(D-3). */
	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE EventSubmission s
		SET s.status = com.msg.fillmap.event.submission.entity.EventSubmissionStatus.REJECTED,
			s.updatedAt = :now
		WHERE s.id = :id
			AND s.status = com.msg.fillmap.event.submission.entity.EventSubmissionStatus.IN_REVIEW
		""")
	int rejectInReview(@Param("id") Long id, @Param("now") LocalDateTime now);

	/**
	 * 노출 중지 (MSG-500 D-3) — 승인됐고 아직 중지되지 않은 행에만 걸린다. 조건부 UPDATE 한 문장이라
	 * 동시 중지 두 건이 둘 다 성공해 사유가 덮이는 경합이 없고, 0행이면 호출자가 재조회로 가른다:
	 * 없거나 미승인이면 13430, 이미 중지면 13453 이다.
	 * <p>
	 * {@code flushAutomatically} 를 걸지 않은 것은 이 전이 앞에 엔티티 쓰기가 없기 때문이다(읽기만 한다).
	 * 나중에 앞쪽에 엔티티 수정이 생기면 그 변경이 flush 되지 않은 채 이 UPDATE 가 먼저 나가 순서가
	 * 뒤집히므로, 그때는 플래그가 필요하다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE EventSubmission s
		SET s.unpublishedAt = :now, s.unpublishReason = :reason, s.updatedAt = :now
		WHERE s.id = :id
			AND s.status = com.msg.fillmap.event.submission.entity.EventSubmissionStatus.APPROVED
			AND s.unpublishedAt IS NULL
		""")
	int unpublishApproved(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

	/**
	 * 승인 행사 목록 (MSG-500 §API 5, D-11) — 원천은 신청 테이블의 APPROVED 행이고 안정 식별자는 신청 id 다.
	 * 탭 상태는 저장하지 않고 <b>KST 오늘</b>과 기간으로 파생한다: 시작 전이면 UPCOMING, 기간 안이면 EXPOSED,
	 * 종료 후면 ENDED 다(경계일 양끝 포함). 그래서 today 가 파라미터이고, 같은 CASE 가 항목의 status 필드도
	 * 만든다 — 필터와 표시가 한 식에서 나와야 둘이 어긋나지 않는다.
	 * <p>
	 * 중지된 행사도 파생 탭에 그대로 남는다(D-11) — 목록에서 지우지 않고 unpublished 필드로 구분한다.
	 * 기관명은 심사 큐와 같은 이유로 users 세타 조인이다.
	 */
	@Query(value = """
		SELECT new com.msg.fillmap.event.submission.dto.AdminApprovedEventItemResponseDto(
			s.id, s.approvalNo, s.submissionNo, s.type, s.title, s.organizerName, u.orgName,
			s.startsOn, s.endsOn,
			CASE WHEN s.startsOn > :today THEN 'UPCOMING'
				WHEN s.endsOn < :today THEN 'ENDED'
				ELSE 'EXPOSED' END,
			s.unpublishedAt, s.unpublishReason)
		FROM EventSubmission s, User u
		WHERE u.id = s.userId
			AND s.status = com.msg.fillmap.event.submission.entity.EventSubmissionStatus.APPROVED
			AND (CASE WHEN s.startsOn > :today THEN 'UPCOMING'
				WHEN s.endsOn < :today THEN 'ENDED'
				ELSE 'EXPOSED' END) = :tab
		ORDER BY s.startsOn DESC, s.id DESC
		""",
		countQuery = """
			SELECT COUNT(s) FROM EventSubmission s
			WHERE s.status = com.msg.fillmap.event.submission.entity.EventSubmissionStatus.APPROVED
				AND (CASE WHEN s.startsOn > :today THEN 'UPCOMING'
					WHEN s.endsOn < :today THEN 'ENDED'
					ELSE 'EXPOSED' END) = :tab
			""")
	Page<AdminApprovedEventItemResponseDto> findApprovedPageByTab(@Param("tab") String tab,
		@Param("today") LocalDate today, Pageable pageable);

	/** 탭 뱃지용 건수 — 목록과 같은 파생식을 쓴다(식이 갈리면 뱃지 숫자와 목록 건수가 어긋난다). */
	@Query("""
		SELECT COUNT(s) FROM EventSubmission s
		WHERE s.status = com.msg.fillmap.event.submission.entity.EventSubmissionStatus.APPROVED
			AND (CASE WHEN s.startsOn > :today THEN 'UPCOMING'
				WHEN s.endsOn < :today THEN 'ENDED'
				ELSE 'EXPOSED' END) = :tab
		""")
	long countApprovedByTab(@Param("tab") String tab, @Param("today") LocalDate today);

	/**
	 * 신청 번호의 순번 (D-4). 연도별 리셋이 없는 전역 증가값이라 리셋 기계 없이 UNIQUE 가 보장된다.
	 * PostgreSQL 시퀀스 함수라 native 다 (JPA 표준에 동등 표현이 없다).
	 */
	@Query(value = "SELECT nextval('event_submission_no_seq')", nativeQuery = true)
	long nextSubmissionSequence();

	/** 승인 번호의 순번 (MSG-500 D-4) — 신청 번호와 같은 구조의 전역 시퀀스다(연도별 리셋 없음). */
	@Query(value = "SELECT nextval('event_submission_approval_no_seq')", nativeQuery = true)
	long nextApprovalSequence();
}

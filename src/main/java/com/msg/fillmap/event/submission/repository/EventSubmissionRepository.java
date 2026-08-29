package com.msg.fillmap.event.submission.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.event.submission.entity.EventSubmission;

public interface EventSubmissionRepository extends JpaRepository<EventSubmission, Long> {

	/**
	 * 소유 조회 (FR-14). 조회를 항상 id + userId 쌍으로 하는 것이 존재 은닉의 구현이다 — 없는 신청과 남의
	 * 신청이 같은 빈 결과가 되어 응답이 갈릴 코드 경로 자체가 없다.
	 */
	@EntityGraph(attributePaths = "locations")
	Optional<EventSubmission> findByIdAndUserId(Long id, Long userId);

	/** 내 신청 목록 (FR-11). 페이지네이션 없음 — 내부 소수 사용자다. id 는 같은 시각 제출의 결정성 보험이다. */
	List<EventSubmission> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

	@Query("""
		SELECT new com.msg.fillmap.event.submission.repository.EventSubmissionStatusCount(s.status, COUNT(s))
		FROM EventSubmission s
		WHERE s.userId = :userId
		GROUP BY s.status
		""")
	List<EventSubmissionStatusCount> countByStatus(@Param("userId") Long userId);

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
	 * 신청 번호의 순번 (D-4). 연도별 리셋이 없는 전역 증가값이라 리셋 기계 없이 UNIQUE 가 보장된다.
	 * PostgreSQL 시퀀스 함수라 native 다 (JPA 표준에 동등 표현이 없다).
	 */
	@Query(value = "SELECT nextval('event_submission_no_seq')", nativeQuery = true)
	long nextSubmissionSequence();
}

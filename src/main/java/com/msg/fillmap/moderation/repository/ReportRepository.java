package com.msg.fillmap.moderation.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.moderation.dto.AdminReportItemResponseDto;
import com.msg.fillmap.moderation.entity.Report;
import com.msg.fillmap.moderation.entity.ReportStatus;

public interface ReportRepository extends JpaRepository<Report, Long> {

	/**
	 * 중복 신고 선제 검사 (FR-2). status 무관 — 처리 완료된 신고가 있어도 재신고는 막힌다 (§D3).
	 * V27 유니크 제약(uq_reports_reporter_video)이 만든 인덱스를 탄다.
	 */
	boolean existsByReporterIdAndVideoId(Long reporterId, Long videoId);

	/**
	 * 승인·기각용 신고 행 잠금 조회 (MSG-195 FR-10). SELECT ... FOR UPDATE 로 같은 신고의 동시 처리를
	 * 직렬화한다 — 늦은 트랜잭션은 잠금 대기 후 이미 처리된 상태를 읽고 11410 으로 걸린다
	 * (FriendshipRepository.findWithLockById 선례의 파생 쿼리 + @Lock 방식).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Report> findWithLockById(Long id);

	/**
	 * 승인 잠금 전 무잠금 videoId 선독 (Codex 지적 후속). 엔티티가 아니라 스칼라만 읽는 이유:
	 * Report 를 먼저 로드하면 이후 findWithLockById 가 1차 캐시의 같은 인스턴스를 돌려줘 잠금 사이에
	 * 바뀐 상태(PENDING → 처리됨)를 못 본다. videoId 는 신고 행에서 불변이라 무잠금 선독이 안전하다.
	 */
	@Query("SELECT r.videoId FROM Report r WHERE r.id = :reportId")
	Optional<Long> findVideoIdById(@Param("reportId") Long reportId);

	/**
	 * 관리자 신고 목록 (MSG-195 FR-1·FR-2). 신고자·영상·영상 소유자를 FK 매핑 없이 id 로 잇는 세타 조인
	 * 한 방이라 항목당 추가 쿼리가 없다 (FriendshipRepository 의 "User 를 Long 조인" 선례).
	 * 조인 결측은 없다 — 신고자는 FK(RESTRICT)로, 영상·소유자는 reports.video_id 의 ON DELETE CASCADE 로
	 * 행 존재가 보장된다(영상이 지워지면 신고 행도 함께 사라진다).
	 *
	 * <p>정렬은 접수 최신순 고정이고 id DESC 는 같은 시각 접수의 순서를 안정화하는 타이브레이크다.
	 * 기본 필터 PENDING 은 V1 부분 인덱스 idx_reports_pending(created_at)이 그대로 커버한다.
	 * count 쿼리는 조인이 필요 없어 reports 단독으로 따로 준다.
	 */
	@Query(value = """
		SELECT new com.msg.fillmap.moderation.dto.AdminReportItemResponseDto(
			r.id, r.status, r.reason, r.detail, r.createdAt,
			r.reporterId, ru.nickname,
			r.videoId, v.status, vu.nickname,
			r.reviewedBy, r.reviewedAt)
		FROM Report r, User ru, Video v, User vu
		WHERE ru.id = r.reporterId AND v.id = r.videoId AND vu.id = v.userId
			AND r.status = :status
		ORDER BY r.createdAt DESC, r.id DESC
		""",
		countQuery = "SELECT count(r) FROM Report r WHERE r.status = :status")
	Page<AdminReportItemResponseDto> findPageByStatus(@Param("status") ReportStatus status, Pageable pageable);
}

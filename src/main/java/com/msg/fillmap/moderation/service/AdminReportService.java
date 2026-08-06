package com.msg.fillmap.moderation.service;

import com.msg.fillmap.moderation.dto.AdminReportListResponseDto;
import com.msg.fillmap.moderation.dto.AdminReportProcessResponseDto;

/**
 * 관리자 신고 처리 (MSG-195). 접수(ReportService)로 쌓인 PENDING 신고를 관리자가 열람하고 종결하는 축이다.
 * 호출자는 ADMIN role 이 확인된 요청뿐이다 — role 검사는 SecurityConfig 의 /api/admin/** matcher 가 하고,
 * 이 서비스는 인가를 다시 판정하지 않는다.
 */
public interface AdminReportService {

	/**
	 * 신고 목록 조회 (FR-1·FR-2). 상태 필터 기준 접수 최신순 오프셋 페이징이다. 거부 판정은 처음 걸린
	 * 하나를 돌려준다: status 파싱 실패(11420) → page·size 범위 밖(11421).
	 * REVIEWING 은 유효한 필터 값이지만 만드는 경로가 없어 항상 빈 목록이다.
	 */
	AdminReportListResponseDto getReports(String status, int page, int size);

	/**
	 * 신고 승인 (FR-4·FR-5). 신고 종결(RESOLVED)과 영상 블라인드를 한 트랜잭션으로 묶는다 — 롤백 시 두
	 * 변경이 함께 사라진다. 영상이 이미 BLINDED 거나 DELETED 면 전이 없이 신고만 종결한다 (FR-5).
	 * 없는 신고는 11404, 이미 처리된 신고와 동시 처리의 패자는 11410 이다 (FR-7·FR-10).
	 *
	 * @param adminId 처리하는 관리자의 사용자 id — reviewed_by 에 기록된다
	 */
	AdminReportProcessResponseDto approve(Long adminId, Long reportId);

	/**
	 * 신고 기각 (FR-6). 신고만 REJECTED 로 종결하고 영상 축에는 아무 영향이 없다.
	 * 거부 판정(11404·11410)은 승인과 같다.
	 *
	 * @param adminId 처리하는 관리자의 사용자 id — reviewed_by 에 기록된다
	 */
	AdminReportProcessResponseDto reject(Long adminId, Long reportId);
}

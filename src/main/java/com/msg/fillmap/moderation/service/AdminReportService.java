package com.msg.fillmap.moderation.service;

import com.msg.fillmap.moderation.dto.AdminReportListResponseDto;

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
}

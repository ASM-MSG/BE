package com.msg.fillmap.moderation.service;

import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.moderation.dto.AdminReportListResponseDto;
import com.msg.fillmap.moderation.entity.ReportStatus;
import com.msg.fillmap.moderation.exception.ReportErrorCode;
import com.msg.fillmap.moderation.repository.ReportRepository;

/**
 * 관리자 신고 처리 (MSG-195).
 */
@Service
@RequiredArgsConstructor
public class AdminReportServiceImpl implements AdminReportService {

	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;

	private final ReportRepository reportRepository;

	@Override
	@Transactional(readOnly = true)
	public AdminReportListResponseDto getReports(String status, int page, int size) {
		ReportStatus filter = parseStatus(status);
		// PageRequest.of 에 그냥 넘기면 IllegalArgumentException 이 catch-all 핸들러에서 500 이 된다 —
		// 잘못된 페이지 요청은 클라이언트 잘못이므로 400 으로 먼저 거른다.
		if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
			throw new ApiException(ReportErrorCode.INVALID_PAGE_REQUEST);
		}
		// 정렬 없는 PageRequest — 정렬은 쿼리의 ORDER BY 고정이라 Pageable 이 덧붙이면 안 된다.
		return AdminReportListResponseDto.from(reportRepository.findPageByStatus(filter, PageRequest.of(page, size)));
	}

	/**
	 * 클라이언트 문자열 → ReportStatus (ReportServiceImpl.parseReason 선례). 조용한 기본값 폴백 없이 미지
	 * 값을 11420 으로 거른다. Locale.ROOT 는 터키어 로케일 JVM 의 대문자 변환 차이를 막는다.
	 */
	private ReportStatus parseStatus(String status) {
		try {
			return ReportStatus.valueOf(status.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(ReportErrorCode.INVALID_STATUS_FILTER);
		}
	}
}

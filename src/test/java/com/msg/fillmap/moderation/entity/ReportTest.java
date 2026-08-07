package com.msg.fillmap.moderation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 신고 상태 전이 (MSG-195). 엔티티는 상태·처리자·처리 시각 기록만 한다 — PENDING 확인 같은 전이
 * 가드는 행 잠금을 쥔 서비스 몫이다 (Video.markBlinded 선례). 여기서는 기록 자체가 빠짐없이
 * 남는지만 본다.
 */
@DisplayName("Report 신고 처리 전이")
class ReportTest {

	private static final long ADMIN_ID = 77L;

	private Report pendingReport() {
		return Report.create(3L, 1042L, ReportReason.INAPPROPRIATE, null);
	}

	@Test
	@DisplayName("승인 처리는 RESOLVED 로 바꾸고 처리자와 처리 시각을 남긴다 (FR-4)")
	void 승인_처리는_RESOLVED로_바꾸고_처리자와_처리_시각을_남긴다() {
		Report report = pendingReport();
		LocalDateTime before = LocalDateTime.now();

		report.resolve(ADMIN_ID);

		assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
		assertThat(report.getReviewedBy()).isEqualTo(ADMIN_ID);
		assertThat(report.getReviewedAt()).isNotNull().isAfterOrEqualTo(before);
	}

	@Test
	@DisplayName("기각 처리는 REJECTED 로 바꾸고 처리자와 처리 시각을 남긴다 (FR-6)")
	void 기각_처리는_REJECTED로_바꾸고_처리자와_처리_시각을_남긴다() {
		Report report = pendingReport();
		LocalDateTime before = LocalDateTime.now();

		report.reject(ADMIN_ID);

		assertThat(report.getStatus()).isEqualTo(ReportStatus.REJECTED);
		assertThat(report.getReviewedBy()).isEqualTo(ADMIN_ID);
		assertThat(report.getReviewedAt()).isNotNull().isAfterOrEqualTo(before);
	}

	@Test
	@DisplayName("접수 직후에는 처리 이력이 비어 있다")
	void 접수_직후에는_처리_이력이_비어_있다() {
		Report report = pendingReport();

		assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
		assertThat(report.getReviewedBy()).isNull();
		assertThat(report.getReviewedAt()).isNull();
	}
}

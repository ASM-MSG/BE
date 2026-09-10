package com.msg.fillmap.moderation.service;

import com.msg.fillmap.moderation.dto.ReportCreateRequestDto;
import com.msg.fillmap.moderation.dto.ReportCreateResponseDto;

public interface ReportService {

	/**
	 * 영상 신고 접수 (FR-1~5a). PENDING 행 1개를 만드는 것이 전부다 — 자동 블라인드도, 신고 건수
	 * 집계도, 알림도 없다. 거부 판정은 정해진 순서로 처음 걸린 하나를 돌려준다(순서가 계약이다):
	 * reason 파싱(11400) → OTHER 상세 필수(11401) → 영상 없음·DELETED·BLINDED(3404 존재 은닉) →
	 * 자기 영상(11402) → 중복 신고(11409).
	 */
	ReportCreateResponseDto report(Long userId, Long videoId, ReportCreateRequestDto request);
}

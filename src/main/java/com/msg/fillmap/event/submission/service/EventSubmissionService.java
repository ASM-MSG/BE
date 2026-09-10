package com.msg.fillmap.event.submission.service;

import com.msg.fillmap.event.submission.dto.EventSubmissionCreateRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionDetailResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionImagePresignRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionImagePresignResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionMyListResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionSubmitResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionUpdateRequestDto;

/**
 * 행사 등재 신청 (MSG-498). 모든 메서드가 userId 를 첫 인자로 받는다 — 소유권 판정이 서비스 계층 몫이고
 * (경로 인가는 {@code /api/org/**} matcher 가 ORG 까지만 좁힌다), 조회를 항상 id + userId 쌍으로 해야
 * 없는 신청과 남의 신청이 같은 실패가 되기 때문이다 (FR-14).
 */
public interface EventSubmissionService {

	EventSubmissionImagePresignResponseDto issueImagePresignedUrl(Long userId,
		EventSubmissionImagePresignRequestDto request);

	EventSubmissionSubmitResponseDto submit(Long userId, EventSubmissionCreateRequestDto request);

	EventSubmissionMyListResponseDto getMySubmissions(Long userId);

	EventSubmissionDetailResponseDto getSubmission(Long userId, Long submissionId);

	EventSubmissionSubmitResponseDto resubmit(Long userId, Long submissionId,
		EventSubmissionUpdateRequestDto request);
}

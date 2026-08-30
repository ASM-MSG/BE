package com.msg.fillmap.event.submission.service;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.submission.dto.AdminEventSubmissionDetailResponseDto;
import com.msg.fillmap.event.submission.dto.AdminEventSubmissionListResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionAreaRectDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionHistoryResponseDto;
import com.msg.fillmap.event.submission.entity.EventSubmission;
import com.msg.fillmap.event.submission.entity.EventSubmissionAreaRect;
import com.msg.fillmap.event.submission.entity.EventSubmissionLocation;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatus;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.event.submission.repository.EventSubmissionStatusHistoryRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 관리자 심사 조회 (MSG-500 §API 1·2). 행사 운영자 조회(MSG-498)와 <b>존재 은닉이 반대다</b> — 소유 술어가
 * 없고 없는 id 는 그대로 404(13430)다. 관리자는 전체를 보는 주체라 숨길 대상이 없다.
 *
 * <p>역할 검사는 여기에 없다. SecurityConfig 의 {@code /api/admin/**} matcher 가 필터 단계에서 거른다
 * (AdminOrgAccountController 선례).
 */
@Service
@RequiredArgsConstructor
public class AdminEventSubmissionService {

	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;

	private final EventSubmissionRepository submissionRepository;
	private final EventSubmissionStatusHistoryRepository historyRepository;
	private final EventSubmissionImageStore imageStore;
	private final EventSubmissionLocationView locationView;
	private final UserRepository userRepository;

	/**
	 * 심사 큐 (§API 1). 상태 필터 기준 접수 최신순 오프셋 페이징이고, 탭 뱃지용 건수 3종은 필터와 무관한
	 * 전체 집계다. 거부 판정은 처음 걸린 하나를 돌려준다: 상태 파싱 실패(13455) → 페이지 범위 밖(13456).
	 */
	@Transactional(readOnly = true)
	public AdminEventSubmissionListResponseDto getSubmissions(String status, int page, int size) {
		EventSubmissionStatus filter = parseStatus(status);
		// PageRequest.of 에 그냥 넘기면 IllegalArgumentException 이 catch-all 핸들러에서 500 이 된다.
		// 오프셋(page*size)이 int 를 넘는 극단 양수도 같다 (MSG-499 관리자 큐 선례, 대역만 event 다).
		if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE
			|| (long) page * size > Integer.MAX_VALUE) {
			throw new ApiException(EventErrorCode.INVALID_PAGE_RANGE);
		}
		return AdminEventSubmissionListResponseDto.of(
			submissionRepository.findAdminPageByStatus(filter, PageRequest.of(page, size)),
			submissionRepository.countByStatus(EventSubmissionStatus.IN_REVIEW),
			submissionRepository.countByStatus(EventSubmissionStatus.APPROVED),
			submissionRepository.countByStatus(EventSubmissionStatus.REJECTED));
	}

	/**
	 * 심사 상세 (§API 2). 폼 필드 전체에 심사 재료 셋을 더한다 — 신청 계정 정보, 노출 영역 사각형, 이력이다.
	 * 위치 표현은 행사 운영자 상세와 같은 조립기를 쓴다({@link EventSubmissionLocationView}) — 심사자와
	 * 신청자가 서로 다른 그림을 보면 반려 사유가 가리키는 대상이 어긋난다.
	 */
	@Transactional(readOnly = true)
	public AdminEventSubmissionDetailResponseDto getSubmission(Long submissionId) {
		EventSubmission submission = submissionRepository.findWithLocationsById(submissionId)
			.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
		// 신청 계정은 FK 가 보장하는 존재라 빈 결과가 정상 흐름에 없다 — 그래도 삼키지 않고 1404 로 드러낸다.
		User applicant = userRepository.findById(submission.getUserId())
			.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));

		return new AdminEventSubmissionDetailResponseDto(
			submission.getId(),
			submission.getSubmissionNo(),
			submission.getType().name(),
			submission.getStatus().name(),
			submission.getTitle(),
			submission.getOrganizerName(),
			submission.getStartsOn(),
			submission.getEndsOn(),
			submission.getOperatingHours(),
			submission.getProgramDescription(),
			submission.getDescription(),
			imageStore.presignGet(submission.getImageKey()),
			applicant.getOrgName(),
			applicant.getNickname(),
			applicant.getEmail(),
			locationView.describe(submission.getLocations()),
			exposureRect(submission.getLocations()),
			historyRepository.findByEventSubmissionIdOrderByIdAsc(submissionId).stream()
				.map(EventSubmissionHistoryResponseDto::from)
				.toList(),
			submission.getCreatedAt(),
			submission.getUpdatedAt());
	}

	/**
	 * 노출 영역 (§API 2) — 전 위치 사각형을 감싸는 경계 사각형이다. 셀을 전개하지 않고 사각형의 min·max 만
	 * 접는 것으로 충분하다: 합집합의 경계는 원본 사각형들의 경계와 같다. 조회 시점 계산이라 저장하지 않는다.
	 * <p>
	 * 선행 조건은 "위치 1개 이상, 위치마다 사각형 1개 이상"이고 접수 검증(MSG-498 buildLocations·expand)이
	 * 그 둘을 강제한다 — 신청을 만드는 경로가 그 애그리거트 하나뿐이라 빈 입력이 여기 닿지 못한다.
	 * 그래서 빈 경우의 분기를 두지 않는다(응답 필드도 non-null 계약이다).
	 */
	private EventSubmissionAreaRectDto exposureRect(List<EventSubmissionLocation> locations) {
		int minGridY = Integer.MAX_VALUE;
		int maxGridY = Integer.MIN_VALUE;
		int minGridX = Integer.MAX_VALUE;
		int maxGridX = Integer.MIN_VALUE;
		for (EventSubmissionLocation location : locations) {
			for (EventSubmissionAreaRect rect : location.getRects()) {
				minGridY = Math.min(minGridY, rect.getMinGridY());
				maxGridY = Math.max(maxGridY, rect.getMaxGridY());
				minGridX = Math.min(minGridX, rect.getMinGridX());
				maxGridX = Math.max(maxGridX, rect.getMaxGridX());
			}
		}
		return new EventSubmissionAreaRectDto(minGridY, maxGridY, minGridX, maxGridX);
	}

	/** 클라이언트 문자열 → 상태 (MSG-499 parseStatus 선례). 조용한 기본값 폴백 없이 13455 로 거른다. */
	private EventSubmissionStatus parseStatus(String status) {
		try {
			return EventSubmissionStatus.valueOf(status.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(EventErrorCode.INVALID_SUBMISSION_STATUS_FILTER);
		}
	}
}

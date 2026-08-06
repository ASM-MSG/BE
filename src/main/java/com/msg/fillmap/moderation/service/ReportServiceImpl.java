package com.msg.fillmap.moderation.service;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.moderation.dto.ReportCreateRequestDto;
import com.msg.fillmap.moderation.dto.ReportCreateResponseDto;
import com.msg.fillmap.moderation.entity.Report;
import com.msg.fillmap.moderation.entity.ReportReason;
import com.msg.fillmap.moderation.exception.ReportErrorCode;
import com.msg.fillmap.moderation.repository.ReportRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 영상 신고 접수 (MSG-192). 영상 검증은 VideoRepository 를 직접 주입해 수행한다 — moderation 과 video
 * 는 둘 다 Owner B 라 A·B 경계의 계약 인터페이스가 필요한 자리가 아니다 (§D4).
 */
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

	private final ReportRepository reportRepository;
	private final VideoRepository videoRepository;

	@Override
	@Transactional
	public ReportCreateResponseDto report(Long userId, Long videoId, ReportCreateRequestDto request) {
		// 요청 형식 오류를 영상 조회보다 먼저 거른다 — 형식이 틀린 요청이 영상 존재 여부를 알아내는 창구가 되지 않게.
		ReportReason reason = parseReason(request.reason());
		if (reason == ReportReason.OTHER && (request.detail() == null || request.detail().isBlank())) {
			throw new ApiException(ReportErrorCode.DETAIL_REQUIRED);
		}

		Video video = videoRepository.findById(videoId)
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		// DELETED·BLINDED 는 재생 경로(MSG-206)와 같은 404 존재 은닉이다. 소유자 예외는 두지 않는다 —
		// 자기 영상 신고 자체가 불가라 소유자·타인을 구분할 이유가 없다.
		if (video.getStatus() != VideoStatus.ACTIVE) {
			throw new ApiException(VideoErrorCode.VIDEO_NOT_FOUND);
		}
		if (video.getUserId().equals(userId)) {
			throw new ApiException(ReportErrorCode.SELF_REPORT);
		}
		if (reportRepository.existsByReporterIdAndVideoId(userId, videoId)) {
			throw new ApiException(ReportErrorCode.DUPLICATE_REPORT);
		}

		try {
			// saveAndFlush: 같은 순간 들어온 두 요청은 위 exists 검사를 둘 다 통과할 수 있다. flush 를 커밋까지
			// 미루면 유니크 위반이 이 catch 바깥(트랜잭션 커밋)에서 터져 500 이 되므로 위반 시점을 앞당긴다 (§D2).
			Report saved = reportRepository.saveAndFlush(Report.create(userId, videoId, reason, request.detail()));
			return ReportCreateResponseDto.from(saved);
		} catch (DataIntegrityViolationException e) {
			throw new ApiException(ReportErrorCode.DUPLICATE_REPORT, e);
		}
	}

	/**
	 * 클라이언트 문자열 → ReportReason. VideoServiceImpl.parseVisibility 선례 — 조용한 기본값 폴백 없이
	 * 미지 값을 11400 으로 거른다. Locale.ROOT 는 터키어 로케일 JVM 의 대문자 변환 차이를 막는다.
	 */
	private ReportReason parseReason(String reason) {
		try {
			return ReportReason.valueOf(reason.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(ReportErrorCode.INVALID_REASON);
		}
	}
}

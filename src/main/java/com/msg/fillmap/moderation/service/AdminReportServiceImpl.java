package com.msg.fillmap.moderation.service;

import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.moderation.dto.AdminReportListResponseDto;
import com.msg.fillmap.moderation.dto.AdminReportProcessResponseDto;
import com.msg.fillmap.moderation.dto.AdminVideoReviewResponseDto;
import com.msg.fillmap.moderation.dto.AdminVideoUnblindResponseDto;
import com.msg.fillmap.moderation.entity.Report;
import com.msg.fillmap.moderation.entity.ReportStatus;
import com.msg.fillmap.moderation.exception.ReportErrorCode;
import com.msg.fillmap.moderation.repository.ReportRepository;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.service.VideoModerationService;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 관리자 신고 처리 (MSG-195). VideoRepository 직접 주입은 MSG-192 §D4 선례다 — moderation 과 video 는
 * 둘 다 Owner B 라 계약 인터페이스가 필요한 경계가 아니다. 블라인드 전이 자체는 MSG-193 의
 * {@link VideoModerationService} 호출만 하고 video 쪽 코드는 건드리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminReportServiceImpl implements AdminReportService {

	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;

	private final ReportRepository reportRepository;
	private final VideoRepository videoRepository;
	// 소유자 users KEY SHARE 선취용 (MSG-417 스펙 2절) — approve 가 videos 를 먼저 잠그는 유일한 경로.
	private final UserRepository userRepository;
	private final VideoModerationService videoModerationService;
	private final ThumbnailUrlPresigner thumbnailUrlPresigner;

	@Override
	@Transactional(readOnly = true)
	public AdminReportListResponseDto getReports(String status, int page, int size) {
		ReportStatus filter = parseStatus(status);
		// PageRequest.of 에 그냥 넘기면 IllegalArgumentException 이 catch-all 핸들러에서 500 이 된다 —
		// 잘못된 페이지 요청은 클라이언트 잘못이므로 400 으로 먼저 거른다. 오프셋(page*size)이 int 를 넘는
		// 극단 양수도 같다 — JPA firstResult 가 int 라 Spring Data 가 변환 예외를 던져 500 이 된다 (Codex 1R).
		if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE
			|| (long) page * size > Integer.MAX_VALUE) {
			throw new ApiException(ReportErrorCode.INVALID_PAGE_REQUEST);
		}
		// 정렬 없는 PageRequest — 정렬은 쿼리의 ORDER BY 고정이라 Pageable 이 덧붙이면 안 된다.
		return AdminReportListResponseDto.from(reportRepository.findPageByStatus(filter, PageRequest.of(page, size)));
	}

	@Override
	@Transactional
	public AdminReportProcessResponseDto approve(Long adminId, Long reportId) {
		// 잠금 순서는 videos 먼저, reports 다음이다 (Codex 지적으로 §D3 수정) — 소유자 탈퇴의
		// FK CASCADE 가 users → videos → reports 순으로 행을 잠그므로, 신고를 먼저 잠그면 탈퇴와
		// AB-BA 데드락이 된다. videoId 는 신고 행에서 불변이라 무잠금 스칼라 선독으로 읽고,
		// 영상 잠금 확보 뒤 신고를 잠가 PENDING 을 재검증한다(그사이 처리됐으면 11410).
		Long videoId = reportRepository.findVideoIdById(reportId)
			.orElseThrow(() -> new ApiException(ReportErrorCode.REPORT_NOT_FOUND));
		// 소유자 users KEY SHARE 선취 (MSG-417 스펙 2절) — blind 내부의 알림 record 가 users FK 잠금을
		// 요구하는데, 이 메서드는 blind 전에 videos 를 먼저 잠근다. 선취 없이는 순서가 videos → users 로
		// 남아 탈퇴 CASCADE(users → videos)와 교차한다. 빈 결과 = 탈퇴 진행/완료로 신고도 곧 사라질 행 —
		// 아래 영상 행 부재와 같은 상황이라 같은 코드로 알린다.
		Long ownerId = videoRepository.findUserIdById(videoId)   // 무잠금 선독 — user_id 불변
			.orElseThrow(() -> new ApiException(ReportErrorCode.REPORT_NOT_FOUND));
		userRepository.findIdForKeyShare(ownerId)
			.orElseThrow(() -> new ApiException(ReportErrorCode.REPORT_NOT_FOUND));
		// 영상 행 없음 = 선독과 잠금 사이 소유자 탈퇴 CASCADE 로 신고도 함께 사라진 것 — 신고 부재로 알린다.
		Video video = videoRepository.findWithLockById(videoId)
			.orElseThrow(() -> new ApiException(ReportErrorCode.REPORT_NOT_FOUND));
		Report report = lockPendingReport(reportId);
		// FR-5 는 예외 포획이 아니라 잠금 보유 중 상태 사전 확인으로 구현한다 (§D2) — blind 의 3409·3404 를
		// 잡아 분기하면 그쪽 가드 변경에 조용히 깨진다. 위 행 잠금 덕에 확인과 전이 사이 경합도 없다.
		if (video.getStatus() == VideoStatus.ACTIVE) {
			// blind 내부의 findWithLockById 는 이 트랜잭션이 이미 보유한 잠금의 재획득이라 대기가 없고,
			// 같은 영속성 컨텍스트라 위 video 인스턴스를 그대로 돌려받아 전이가 여기 참조에도 반영된다.
			videoModerationService.blind(video.getId());
		}
		report.resolve(adminId);
		return AdminReportProcessResponseDto.of(report, video.getStatus());
	}

	@Override
	@Transactional
	public AdminReportProcessResponseDto reject(Long adminId, Long reportId) {
		Report report = lockPendingReport(reportId);
		report.reject(adminId);
		// 영상 축은 잠그지도 바꾸지도 않는다 (FR-6) — 응답의 videoStatus 만 잠금 없는 일반 조회로 채운다.
		VideoStatus videoStatus = videoRepository.findById(report.getVideoId())
			.map(Video::getStatus)
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		return AdminReportProcessResponseDto.of(report, videoStatus);
	}

	@Override
	public AdminVideoUnblindResponseDto unblindVideo(Long videoId) {
		// 위임 한 줄 (MSG-193 §D6 그대로) — 가드(3404·3409)와 잠금·트랜잭션은 unblind 가 이미 갖췄고,
		// 신고 상태는 되돌리지 않으므로(FR-8) 신고 축은 조회조차 하지 않는다. 감사도 별도 기록 없이
		// 접근 로그로 갈음한다 (§D4) — 그래서 관리자 식별자를 받지 않는다.
		videoModerationService.unblind(videoId);
		// unblind 성공 = ACTIVE 확정이다 (다른 종료 상태는 전부 예외로 나간다) — 재조회 없이 채운다.
		return new AdminVideoUnblindResponseDto(videoId, VideoStatus.ACTIVE);
	}

	@Override
	@Transactional(readOnly = true)
	public AdminVideoReviewResponseDto getVideoForReview(Long videoId) {
		Video video = videoRepository.findById(videoId)
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		if (video.isDeleted()) {
			// DELETED 만 거부한다 — 삭제 경로(MSG-133)가 커밋 후 S3 객체를 지우므로 발급해도 재생할 파일이 없다.
			throw new ApiException(VideoErrorCode.VIDEO_NOT_FOUND);
		}
		// 사용자 재생 경로(getVideoPlayback)를 호출하지 않는다 — 그쪽의 소유자 분기·친구 판정·조회수 증가·
		// BLINDED 은닉이 전부 관리자 맥락에 부적합해서다. status·visibility 는 무시하고 발급한다 (FR-3).
		// 재생 소스 선택 규칙만 MSG-206 과 같다: READY 일 때 블러본이 있으면 블러본, 없으면 인코딩본.
		String playbackUrl = null;
		Long expiresInSec = null;
		if (video.getProcessingStatus() == ProcessingStatus.READY) {
			String playbackKey = video.getBlurredS3Key() != null ? video.getBlurredS3Key() : video.getEncodedUrl();
			playbackUrl = thumbnailUrlPresigner.presign(playbackKey);
			// key 가 null 인 기형 READY 행이면 presign 이 null — playbackUrl 없이 TTL 만 남는 걸 막는다 (MSG-206 선례).
			if (playbackUrl != null) {
				expiresInSec = thumbnailUrlPresigner.ttlSeconds();
			}
		}
		String thumbnailUrl = thumbnailUrlPresigner.presign(video.getThumbnailUrl());
		return AdminVideoReviewResponseDto.of(video, playbackUrl, thumbnailUrl, expiresInSec);
	}

	/**
	 * 신고 행 잠금 조회 + PENDING 사전 확인 — 승인·기각 공통 진입부. 동시 처리 경쟁(FR-10)은 이 행
	 * 잠금이 직렬화한다: 늦은 트랜잭션은 잠금 대기 후 처리된 상태를 읽고 11410 으로 걸린다.
	 */
	private Report lockPendingReport(Long reportId) {
		Report report = reportRepository.findWithLockById(reportId)
			.orElseThrow(() -> new ApiException(ReportErrorCode.REPORT_NOT_FOUND));
		if (report.getStatus() != ReportStatus.PENDING) {
			throw new ApiException(ReportErrorCode.ALREADY_PROCESSED_REPORT);
		}
		return report;
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

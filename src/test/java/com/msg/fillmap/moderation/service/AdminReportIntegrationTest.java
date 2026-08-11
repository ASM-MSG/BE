package com.msg.fillmap.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.moderation.dto.AdminReportItemResponseDto;
import com.msg.fillmap.moderation.dto.AdminReportListResponseDto;
import com.msg.fillmap.moderation.dto.AdminReportProcessResponseDto;
import com.msg.fillmap.moderation.dto.AdminVideoReviewResponseDto;
import com.msg.fillmap.moderation.dto.AdminVideoUnblindResponseDto;
import com.msg.fillmap.moderation.entity.Report;
import com.msg.fillmap.moderation.entity.ReportReason;
import com.msg.fillmap.moderation.entity.ReportStatus;
import com.msg.fillmap.moderation.exception.ReportErrorCode;
import com.msg.fillmap.moderation.repository.ReportRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 관리자 신고 처리 — 목록 조회·승인·기각·블라인드 해제·단건 확인 (MSG-195, 실 DB). @Transactional 롤백
 * 격리 + UUID 이메일 시드로 공유 로컬 DB 에 잔여 행을 남기지 않는다 (ReportIntegrationTest 패턴).
 * 동시 처리 직렬화(FR-10)만 커밋이 필요해 AdminReportApproveConcurrencyTest 로 분리했다.
 * presign 은 AWS 자격증명 의존을 피해 mock 으로 대체하고, key → URL 에코 스텁으로 재생 소스 선택
 * (블러본 우선)까지 검증한다 (VideoGlobalListViewCountIntegrationTest 선례).
 *
 * <p>목록은 테이블 전체를 상태로 거르므로 다른 브랜치·수동 작업이 남긴 행이 같은 페이지에 섞일 수 있다.
 * 그래서 전체 건수나 절대 위치가 아니라 "내가 만든 신고들"만 골라 필드와 상대 순서를 본다 — 공유 DB 의
 * 잔여 데이터에 흔들리지 않는 검증축이다.
 */
@SpringBootTest
@Transactional
@DisplayName("관리자 신고 처리 (실 DB)")
class AdminReportIntegrationTest {

	/** 이 티켓 전용 좌표 — 다른 통합 테스트(성수·여의도·MSG-192 신고)와 격자를 공유하지 않는다. */
	private static final double 관리자_LAT = 37.5219;
	private static final double 관리자_LON = 127.0411;

	@Autowired
	private AdminReportService adminReportService;

	@Autowired
	private ReportRepository reportRepository;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@MockitoBean
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	private Long reporterId;
	private Long ownerId;
	private Long adminId;
	private Long videoId;

	@BeforeEach
	void setUp() {
		reporterId = seedUser("신고자정민");
		ownerId = seedUser("영상주인성민");
		adminId = seedUser("관리자");
		videoId = seedVideo(ownerId);
	}

	private Long seedUser(String nickname) {
		return userRepository.save(
			User.createLocalUser("m195-" + UUID.randomUUID() + "@example.com", "hash", nickname)).getId();
	}

	/** 업로드 서비스(S3·격자 점령·스트릭 부수효과)를 거치지 않고 신고 대상 영상 1행만 만든다. */
	private Long seedVideo(Long userId) {
		String gridId = GridEncoder.encode(관리자_LAT, 관리자_LON);
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		return videoRepository.saveAndFlush(Video.create(
			userId, gridId, "videos/original/" + userId + "/" + System.nanoTime() + ".mp4",
			GeoSupport.toPoint(관리자_LAT, 관리자_LON), (short) 10,
			LocalDateTime.now(ZoneOffset.UTC), Visibility.PUBLIC)).getId();
	}

	private Long seedReport(Long reporter, Long video, ReportReason reason, String detail) {
		return reportRepository.saveAndFlush(Report.create(reporter, video, reason, detail)).getId();
	}

	/** 내가 시드한 신고만 남긴다 — 공유 DB 의 다른 행이 같은 페이지에 섞여도 검증이 흔들리지 않게. */
	private List<AdminReportItemResponseDto> mine(AdminReportListResponseDto response, List<Long> reportIds) {
		return response.items().stream().filter(item -> reportIds.contains(item.reportId())).toList();
	}

	// 검증: FR-MOD-09
	@Test
	@DisplayName("신고 목록 기본 조회는 PENDING 을 최신 접수 순으로 반환한다 (FR-1)")
	void 신고_목록_기본_조회는_PENDING을_최신_접수_순으로_반환한다() {
		Long older = seedReport(reporterId, videoId, ReportReason.SPAM, null);
		Long newer = seedReport(seedUser("두번째신고자"), videoId, ReportReason.PRIVACY, null);

		AdminReportListResponseDto response = adminReportService.getReports("PENDING", 0, 20);

		assertThat(response.page()).isZero();
		assertThat(response.size()).isEqualTo(20);
		assertThat(response.totalElements()).isGreaterThanOrEqualTo(2);
		assertThat(mine(response, List.of(older, newer)))
			.extracting(AdminReportItemResponseDto::reportId)
			.containsExactly(newer, older);
	}

	// 검증: FR-MOD-09
	@Test
	@DisplayName("목록 항목에 신고자와 영상 소유자 닉네임이 담긴다 (FR-2)")
	void 목록_항목에_신고자와_영상_소유자_닉네임이_담긴다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.OTHER, "다른 사람 얼굴이 그대로 나옵니다");

		AdminReportItemResponseDto item = mine(adminReportService.getReports("PENDING", 0, 20), List.of(reportId))
			.getFirst();

		assertThat(item.status()).isEqualTo(ReportStatus.PENDING);
		assertThat(item.reason()).isEqualTo(ReportReason.OTHER);
		assertThat(item.detail()).isEqualTo("다른 사람 얼굴이 그대로 나옵니다");
		assertThat(item.createdAt()).isNotNull();
		assertThat(item.reporterId()).isEqualTo(reporterId);
		assertThat(item.reporterNickname()).isEqualTo("신고자정민");
		assertThat(item.videoId()).isEqualTo(videoId);
		assertThat(item.videoStatus()).isEqualTo(VideoStatus.ACTIVE);
		assertThat(item.videoOwnerNickname()).isEqualTo("영상주인성민");
		assertThat(item.reviewedBy()).isNull();
		assertThat(item.reviewedAt()).isNull();
	}

	// 검증: FR-MOD-09
	@Test
	@DisplayName("상태 필터를 RESOLVED 로 바꾸면 처리된 신고와 처리 이력이 온다 (FR-1)")
	void 상태_필터를_RESOLVED로_바꾸면_처리된_신고와_처리_이력이_온다() {
		Long pending = seedReport(reporterId, videoId, ReportReason.SPAM, null);
		Long resolved = seedReport(seedUser("처리된신고자"), videoId, ReportReason.INAPPROPRIATE, null);
		reportRepository.findById(resolved).orElseThrow().resolve(adminId);
		em.flush();

		AdminReportListResponseDto response = adminReportService.getReports("RESOLVED", 0, 20);

		assertThat(mine(response, List.of(pending))).as("PENDING 은 RESOLVED 필터에 안 잡힌다").isEmpty();
		AdminReportItemResponseDto item = mine(response, List.of(resolved)).getFirst();
		assertThat(item.status()).isEqualTo(ReportStatus.RESOLVED);
		assertThat(item.reviewedBy()).isEqualTo(adminId);
		assertThat(item.reviewedAt()).isNotNull();
	}

	// 검증: FR-MOD-09
	@Test
	@DisplayName("소문자 상태 값도 받는다 — 파싱은 대소문자 무관 (FR-1)")
	void 소문자_상태_값도_받는다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.SPAM, null);

		assertThat(mine(adminReportService.getReports("pending", 0, 20), List.of(reportId))).hasSize(1);
	}

	// 검증: FR-MOD-09
	@Test
	@DisplayName("REVIEWING 은 유효한 필터지만 만드는 경로가 없어 빈 목록이다")
	void REVIEWING은_유효한_필터지만_빈_목록이다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.SPAM, null);

		assertThat(mine(adminReportService.getReports("REVIEWING", 0, 20), List.of(reportId))).isEmpty();
	}

	// 검증: FR-MOD-09
	@Test
	@DisplayName("size 만큼 끊어 내려주고 다음 페이지에 나머지가 온다 (§D5 오프셋 페이징)")
	void size_만큼_끊어_내려주고_다음_페이지에_나머지가_온다() {
		Long first = seedReport(reporterId, videoId, ReportReason.SPAM, null);
		Long second = seedReport(seedUser("페이징신고자"), videoId, ReportReason.PRIVACY, null);

		// 방금 만든 두 건이 테이블에서 가장 최신이라, 최신순 페이지 0·1 에 이 순서로 정확히 놓인다.
		AdminReportListResponseDto page0 = adminReportService.getReports("PENDING", 0, 1);
		AdminReportListResponseDto page1 = adminReportService.getReports("PENDING", 1, 1);

		assertThat(page0.items()).extracting(AdminReportItemResponseDto::reportId).containsExactly(second);
		assertThat(page1.items()).extracting(AdminReportItemResponseDto::reportId).containsExactly(first);
		assertThat(page1.page()).isEqualTo(1);
		assertThat(page0.size()).isEqualTo(1);
		assertThat(page0.totalPages()).isEqualTo((int) page0.totalElements());
	}

	// 검증: FR-MOD-09
	@Test
	@DisplayName("지원하지 않는 상태 값은 400 이다 (11420)")
	void 지원하지_않는_상태_값은_400이다() {
		assertThatThrownBy(() -> adminReportService.getReports("BOGUS", 0, 20))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.INVALID_STATUS_FILTER);
	}

	@Test
	@DisplayName("size 상한 100 을 넘기면 400 이다 (11421)")
	void size_상한_100을_넘기면_400이다() {
		assertThatThrownBy(() -> adminReportService.getReports("PENDING", 0, 101))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.INVALID_PAGE_REQUEST);
		// 경계값 100 은 통과해야 한다 — 상한이 한 칸 밀리는 회귀 방지.
		assertThat(adminReportService.getReports("PENDING", 0, 100).size()).isEqualTo(100);
	}

	@Test
	@DisplayName("size 0 이하는 400 이다 (11421)")
	void size_0_이하는_400이다() {
		assertThatThrownBy(() -> adminReportService.getReports("PENDING", 0, 0))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.INVALID_PAGE_REQUEST);
	}

	@Test
	@DisplayName("page 음수는 400 이다 (11421)")
	void page_음수는_400이다() {
		assertThatThrownBy(() -> adminReportService.getReports("PENDING", -1, 20))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.INVALID_PAGE_REQUEST);
	}

	@Test
	@DisplayName("오프셋이 int 를 넘는 극단 page 도 400 이다 (11421 — 500 회귀 방지, Codex 1R)")
	void 오프셋이_int를_넘는_극단_page도_400이다() {
		assertThatThrownBy(() -> adminReportService.getReports("PENDING", Integer.MAX_VALUE, 100))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.INVALID_PAGE_REQUEST);
	}

	// 검증: FR-MOD-11
	@Test
	@DisplayName("승인하면 신고가 RESOLVED, 영상이 BLINDED 가 된다 (FR-4)")
	void 승인하면_신고가_RESOLVED_영상이_BLINDED가_된다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.INAPPROPRIATE, null);

		AdminReportProcessResponseDto response = adminReportService.approve(adminId, reportId);

		assertThat(response.reportId()).isEqualTo(reportId);
		assertThat(response.status()).isEqualTo(ReportStatus.RESOLVED);
		assertThat(response.videoId()).isEqualTo(videoId);
		assertThat(response.videoStatus()).isEqualTo(VideoStatus.BLINDED);
		assertThat(reportRepository.findById(reportId).orElseThrow().getStatus()).isEqualTo(ReportStatus.RESOLVED);
		assertThat(videoRepository.findById(videoId).orElseThrow().getStatus()).isEqualTo(VideoStatus.BLINDED);
	}

	// 검증: FR-MOD-11
	@Test
	@DisplayName("승인 시 처리자와 처리 시각이 기록된다 (FR-4)")
	void 승인_시_처리자와_처리_시각이_기록된다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.SPAM, null);

		AdminReportProcessResponseDto response = adminReportService.approve(adminId, reportId);

		Report processed = reportRepository.findById(reportId).orElseThrow();
		assertThat(processed.getReviewedBy()).isEqualTo(adminId);
		// reviewedAt 은 LocalDateTime.now()(JVM 기본 존)라 UTC 기준 시각과 값 비교하면 존 차이만큼 어긋난다.
		assertThat(processed.getReviewedAt()).isNotNull();
		assertThat(response.reviewedAt()).isNotNull();
	}

	// 검증: FR-MOD-12
	@Test
	@DisplayName("이미 블라인드된 영상의 신고 승인은 영상 전이 없이 신고만 종결한다 (FR-5)")
	void 이미_블라인드된_영상의_신고_승인은_영상_전이_없이_신고만_종결한다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.PRIVACY, null);
		videoRepository.findById(videoId).orElseThrow().markBlinded();
		em.flush();

		AdminReportProcessResponseDto response = adminReportService.approve(adminId, reportId);

		assertThat(response.status()).isEqualTo(ReportStatus.RESOLVED);
		assertThat(response.videoStatus()).isEqualTo(VideoStatus.BLINDED);
		assertThat(reportRepository.findById(reportId).orElseThrow().getStatus()).isEqualTo(ReportStatus.RESOLVED);
	}

	// 검증: FR-MOD-12
	@Test
	@DisplayName("삭제된 영상의 신고 승인도 신고만 종결한다 (FR-5)")
	void 삭제된_영상의_신고_승인도_신고만_종결한다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.COPYRIGHT, null);
		videoRepository.findById(videoId).orElseThrow().markDeleted();
		em.flush();

		AdminReportProcessResponseDto response = adminReportService.approve(adminId, reportId);

		assertThat(response.status()).isEqualTo(ReportStatus.RESOLVED);
		assertThat(response.videoStatus()).isEqualTo(VideoStatus.DELETED);
		assertThat(videoRepository.findById(videoId).orElseThrow().getStatus())
			.as("삭제 영상은 블라인드로 되살리지 않는다").isEqualTo(VideoStatus.DELETED);
	}

	@Test
	@DisplayName("없는 신고의 승인·기각은 404 다 (11404)")
	void 없는_신고의_승인은_404다() {
		assertThatThrownBy(() -> adminReportService.approve(adminId, -1L))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.REPORT_NOT_FOUND);
		assertThatThrownBy(() -> adminReportService.reject(adminId, -1L))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.REPORT_NOT_FOUND);
	}

	// 검증: FR-MOD-12
	@Test
	@DisplayName("이미 처리된 신고의 재승인·재기각은 409 다 (FR-7, 11410)")
	void 이미_처리된_신고의_재승인은_409다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.SPAM, null);
		adminReportService.approve(adminId, reportId);

		assertThatThrownBy(() -> adminReportService.approve(adminId, reportId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.ALREADY_PROCESSED_REPORT);
		assertThatThrownBy(() -> adminReportService.reject(adminId, reportId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.ALREADY_PROCESSED_REPORT);
	}

	// 검증: FR-MOD-11
	@Test
	@DisplayName("기각하면 신고만 REJECTED 가 되고 영상은 그대로다 (FR-6)")
	void 기각하면_신고만_REJECTED가_되고_영상은_그대로다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.SPAM, null);

		AdminReportProcessResponseDto response = adminReportService.reject(adminId, reportId);

		assertThat(response.status()).isEqualTo(ReportStatus.REJECTED);
		assertThat(response.videoStatus()).isEqualTo(VideoStatus.ACTIVE);
		Report processed = reportRepository.findById(reportId).orElseThrow();
		assertThat(processed.getStatus()).isEqualTo(ReportStatus.REJECTED);
		assertThat(processed.getReviewedBy()).isEqualTo(adminId);
		assertThat(processed.getReviewedAt()).isNotNull();
		assertThat(videoRepository.findById(videoId).orElseThrow().getStatus()).isEqualTo(VideoStatus.ACTIVE);
	}

	// 검증: FR-MOD-07, FR-MOD-11
	@Test
	@DisplayName("대표 영상 신고 승인 시 남은 ACTIVE 영상으로 대표가 재선정된다 (blind 위임 확인)")
	void 대표_영상_신고_승인_시_남은_ACTIVE_영상으로_대표가_재선정된다() {
		Long secondVideoId = seedVideo(ownerId);
		String gridId = GridEncoder.encode(관리자_LAT, 관리자_LON);
		videoRepository.upsertUserGrid(ownerId, gridId, videoId);        // 첫 점령: count=1, cover=첫 영상
		videoRepository.upsertUserGrid(ownerId, gridId, secondVideoId);  // 재방문: count=2, cover 유지
		Long reportId = seedReport(reporterId, videoId, ReportReason.INAPPROPRIATE, null);

		adminReportService.approve(adminId, reportId);

		// 상태를 직접 뒤집는 게 아니라 blind 에 위임했음을 대표 재선정 부수효과로 확인한다.
		Number cover = (Number) em.createNativeQuery(
				"SELECT cover_video_id FROM user_grids WHERE user_id = :u AND grid_id = :g")
			.setParameter("u", ownerId).setParameter("g", gridId).getSingleResult();
		assertThat(cover.longValue()).isEqualTo(secondVideoId);
	}

	// 검증: FR-MOD-05, FR-MOD-13
	@Test
	@DisplayName("해제하면 영상이 ACTIVE 로 돌아오고 신고는 RESOLVED 로 남는다 (FR-8)")
	void 해제하면_영상이_ACTIVE로_돌아오고_신고는_RESOLVED로_남는다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.INAPPROPRIATE, null);
		adminReportService.approve(adminId, reportId);

		AdminVideoUnblindResponseDto response = adminReportService.unblindVideo(videoId);

		assertThat(response.videoId()).isEqualTo(videoId);
		assertThat(response.status()).isEqualTo(VideoStatus.ACTIVE);
		assertThat(videoRepository.findById(videoId).orElseThrow().getStatus()).isEqualTo(VideoStatus.ACTIVE);
		assertThat(reportRepository.findById(reportId).orElseThrow().getStatus())
			.as("해제는 신고 상태를 되돌리지 않는다").isEqualTo(ReportStatus.RESOLVED);
	}

	// 검증: FR-MOD-05
	@Test
	@DisplayName("이미 ACTIVE 인 영상의 해제는 409 다 (3409)")
	void 이미_ACTIVE인_영상의_해제는_409다() {
		assertThatThrownBy(() -> adminReportService.unblindVideo(videoId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.ALREADY_IN_TARGET_STATUS);
	}

	// 검증: FR-MOD-05
	@Test
	@DisplayName("삭제된 영상의 해제는 404 다 (3404)")
	void 삭제된_영상의_해제는_404다() {
		videoRepository.findById(videoId).orElseThrow().markDeleted();
		em.flush();

		assertThatThrownBy(() -> adminReportService.unblindVideo(videoId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	// 검증: FR-MOD-10
	@Test
	@DisplayName("BLINDED 영상에도 재생 URL 이 발급되고, 블러본이 있으면 블러본을 서명한다 (FR-3)")
	void BLINDED_영상에도_재생_URL이_발급된다() {
		Video video = videoRepository.findById(videoId).orElseThrow();
		video.markReady("videos/encoded/m195.mp4", "videos/thumb/m195.jpg");
		video.applyBlurResult("videos/blurred/m195.mp4", null);
		video.markBlinded();
		em.flush();
		stubPresignEcho();

		AdminVideoReviewResponseDto response = adminReportService.getVideoForReview(videoId);

		assertThat(response.status()).isEqualTo(VideoStatus.BLINDED);
		assertThat(response.processingStatus()).isEqualTo(ProcessingStatus.READY);
		// 재생 소스 선택은 MSG-206 규칙 그대로 — 블러본 키가 있으면 인코딩본이 아니라 블러본을 서명한다.
		assertThat(response.playbackUrl()).isEqualTo("https://signed/videos/blurred/m195.mp4");
		assertThat(response.thumbnailUrl()).isEqualTo("https://signed/videos/thumb/m195.jpg");
		assertThat(response.expiresInSec()).isEqualTo(600L);
	}

	// 검증: FR-MOD-10
	@Test
	@DisplayName("PRIVATE 영상도 관리자는 확인할 수 있다 — 블러본이 없으면 인코딩본 폴백 (FR-3)")
	void PRIVATE_영상도_관리자는_확인할_수_있다() {
		Video video = videoRepository.findById(videoId).orElseThrow();
		video.changeVisibility(Visibility.PRIVATE);
		video.markReady("videos/encoded/m195.mp4", "videos/thumb/m195.jpg");
		em.flush();
		stubPresignEcho();

		AdminVideoReviewResponseDto response = adminReportService.getVideoForReview(videoId);

		assertThat(response.visibility()).isEqualTo(Visibility.PRIVATE);
		assertThat(response.playbackUrl()).isEqualTo("https://signed/videos/encoded/m195.mp4");
	}

	// 검증: FR-MOD-10
	@Test
	@DisplayName("관리자 확인은 조회수를 올리지 않는다 (FR-3)")
	void 관리자_확인은_조회수를_올리지_않는다() {
		videoRepository.findById(videoId).orElseThrow().markReady("videos/encoded/m195.mp4", "videos/thumb/m195.jpg");
		em.flush();
		stubPresignEcho();

		adminReportService.getVideoForReview(videoId);

		// 조회수 증가는 native UPDATE 라 엔티티가 아니라 DB 행으로 확인한다.
		Number viewCount = (Number) em.createNativeQuery("SELECT view_count FROM videos WHERE id = :v")
			.setParameter("v", videoId).getSingleResult();
		assertThat(viewCount.longValue()).isZero();
	}

	// 검증: FR-MOD-10
	@Test
	@DisplayName("READY 이전 영상은 재생 URL 이 null 이다 (FR-3)")
	void READY_이전_영상은_재생_URL이_null이다() {
		// 시드 기본 상태가 UPLOADED — presign mock 은 스텁 없이 null 을 돌려준다 (key 도 null 이라 정합).
		AdminVideoReviewResponseDto response = adminReportService.getVideoForReview(videoId);

		assertThat(response.processingStatus()).isEqualTo(ProcessingStatus.UPLOADED);
		assertThat(response.playbackUrl()).isNull();
		assertThat(response.thumbnailUrl()).isNull();
		assertThat(response.expiresInSec()).isNull();
	}

	@Test
	@DisplayName("삭제된 영상의 확인은 404 다 (FR-3)")
	void 삭제된_영상의_확인은_404다() {
		videoRepository.findById(videoId).orElseThrow().markDeleted();
		em.flush();

		assertThatThrownBy(() -> adminReportService.getVideoForReview(videoId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	/** presign 을 key 에코 스텁으로 — 어떤 key 를 서명했는지(재생 소스 선택)까지 URL 로 드러난다. */
	private void stubPresignEcho() {
		given(thumbnailUrlPresigner.presign(anyString())).willAnswer(inv -> "https://signed/" + inv.getArgument(0));
		given(thumbnailUrlPresigner.ttlSeconds()).willReturn(600L);
	}
}

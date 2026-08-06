package com.msg.fillmap.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.moderation.dto.AdminReportItemResponseDto;
import com.msg.fillmap.moderation.dto.AdminReportListResponseDto;
import com.msg.fillmap.moderation.entity.Report;
import com.msg.fillmap.moderation.entity.ReportReason;
import com.msg.fillmap.moderation.entity.ReportStatus;
import com.msg.fillmap.moderation.exception.ReportErrorCode;
import com.msg.fillmap.moderation.repository.ReportRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 관리자 신고 목록 조회 (MSG-195, 실 DB). @Transactional 롤백 격리 + UUID 이메일 시드로 공유 로컬 DB 에
 * 잔여 행을 남기지 않는다 (ReportIntegrationTest 패턴).
 *
 * <p>목록은 테이블 전체를 상태로 거르므로 다른 브랜치·수동 작업이 남긴 행이 같은 페이지에 섞일 수 있다.
 * 그래서 전체 건수나 절대 위치가 아니라 "내가 만든 신고들"만 골라 필드와 상대 순서를 본다 — 공유 DB 의
 * 잔여 데이터에 흔들리지 않는 검증축이다.
 */
@SpringBootTest
@Transactional
@DisplayName("관리자 신고 목록 조회 (실 DB)")
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

	@Test
	@DisplayName("소문자 상태 값도 받는다 — 파싱은 대소문자 무관 (FR-1)")
	void 소문자_상태_값도_받는다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.SPAM, null);

		assertThat(mine(adminReportService.getReports("pending", 0, 20), List.of(reportId))).hasSize(1);
	}

	@Test
	@DisplayName("REVIEWING 은 유효한 필터지만 만드는 경로가 없어 빈 목록이다")
	void REVIEWING은_유효한_필터지만_빈_목록이다() {
		Long reportId = seedReport(reporterId, videoId, ReportReason.SPAM, null);

		assertThat(mine(adminReportService.getReports("REVIEWING", 0, 20), List.of(reportId))).isEmpty();
	}

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
}

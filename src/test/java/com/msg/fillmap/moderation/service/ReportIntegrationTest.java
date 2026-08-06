package com.msg.fillmap.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.moderation.dto.ReportCreateRequestDto;
import com.msg.fillmap.moderation.dto.ReportCreateResponseDto;
import com.msg.fillmap.moderation.entity.Report;
import com.msg.fillmap.moderation.entity.ReportReason;
import com.msg.fillmap.moderation.entity.ReportStatus;
import com.msg.fillmap.moderation.exception.ReportErrorCode;
import com.msg.fillmap.moderation.repository.ReportRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 영상 신고 접수 (MSG-192, 실 DB). @Transactional 롤백 격리 + UUID 이메일 시드로 공유 로컬 DB 에
 * 잔여 행을 남기지 않는다 (FriendIntegrationTest 패턴). 핵심 검증 축 = 거부 판정의 first-match 순서와
 * "접수는 reports INSERT 외에 아무것도 바꾸지 않는다"는 부수 효과 없음 계약이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("영상 신고 접수 (실 DB)")
class ReportIntegrationTest {

	/** 이 티켓 전용 좌표 — 다른 통합 테스트(성수·여의도)와 격자를 공유하지 않는다. */
	private static final double 신고_LAT = 37.4979;
	private static final double 신고_LON = 127.0276;
	private static final String URL = "/api/videos/{videoId}/reports";

	@Autowired
	private ReportService reportService;

	@Autowired
	private ReportRepository reportRepository;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	private Long reporterId;
	private Long ownerId;
	private Long videoId;

	@BeforeEach
	void setUp() {
		reporterId = seedUser("신고자");
		ownerId = seedUser("영상주인");
		videoId = seedVideo(ownerId);
	}

	private Long seedUser(String nickname) {
		return userRepository.save(
			User.createLocalUser("report-" + UUID.randomUUID() + "@example.com", "hash", nickname)).getId();
	}

	/** 업로드 서비스(S3·격자 점령·스트릭 부수효과)를 거치지 않고 신고 대상 영상 1행만 만든다. */
	private Long seedVideo(Long userId) {
		String gridId = GridEncoder.encode(신고_LAT, 신고_LON);
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		return videoRepository.saveAndFlush(Video.create(
			userId, gridId, "videos/original/" + userId + "/" + System.nanoTime() + ".mp4",
			GeoSupport.toPoint(신고_LAT, 신고_LON), (short) 10,
			LocalDateTime.now(ZoneOffset.UTC), Visibility.PUBLIC)).getId();
	}

	/**
	 * 영상 상태 직접 전환 — 전환 API(MSG-193)에 의존하지 않는다. 네이티브 UPDATE 라 1차 캐시가 낡으므로
	 * clear 로 비워 서비스의 findById 가 DB 를 다시 읽게 한다.
	 */
	private void setVideoStatus(String status) {
		em.createNativeQuery("UPDATE videos SET status = :s WHERE id = :i")
			.setParameter("s", status).setParameter("i", videoId)
			.executeUpdate();
		em.clear();
	}

	private String videoStatus() {
		return (String) em.createNativeQuery("SELECT status FROM videos WHERE id = :i")
			.setParameter("i", videoId)
			.getSingleResult();
	}

	private long reportCount() {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM reports WHERE reporter_id = :r AND video_id = :v")
			.setParameter("r", reporterId).setParameter("v", videoId)
			.getSingleResult()).longValue();
	}

	private ReportCreateResponseDto report(String reason, String detail) {
		return reportService.report(reporterId, videoId, new ReportCreateRequestDto(reason, detail));
	}

	@Test
	@DisplayName("타인의 ACTIVE 영상 신고는 PENDING 으로 접수되고 reportId 를 반환한다 (FR-1)")
	void 타인의_ACTIVE_영상_신고는_PENDING으로_접수되고_reportId를_반환한다() {
		ReportCreateResponseDto response = report("INAPPROPRIATE", null);

		assertThat(response.reportId()).isNotNull();
		assertThat(response.status()).isEqualTo("PENDING");
		Report saved = reportRepository.findById(response.reportId()).orElseThrow();
		assertThat(saved.getReporterId()).isEqualTo(reporterId);
		assertThat(saved.getVideoId()).isEqualTo(videoId);
		assertThat(saved.getReason()).isEqualTo(ReportReason.INAPPROPRIATE);
		assertThat(saved.getStatus()).isEqualTo(ReportStatus.PENDING);
		assertThat(reportCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("OTHER 가 아닌 사유는 상세 설명 없이 접수된다 (FR-5a)")
	void OTHER가_아닌_사유는_상세_설명_없이_접수된다() {
		ReportCreateResponseDto response = report("privacy", null);

		Report saved = reportRepository.findById(response.reportId()).orElseThrow();
		assertThat(saved.getReason()).isEqualTo(ReportReason.PRIVACY);
		assertThat(saved.getDetail()).isNull();
	}

	@Test
	@DisplayName("상세 설명을 보내면 그대로 저장된다 — OTHER 가 아닌 사유도 포함 (FR-5a)")
	void 상세_설명을_보내면_그대로_저장된다() {
		Report withOther = reportRepository.findById(report("OTHER", "다른 사람 얼굴이 그대로 나옵니다").reportId())
			.orElseThrow();
		assertThat(withOther.getDetail()).isEqualTo("다른 사람 얼굴이 그대로 나옵니다");

		// OTHER 가 아닌 사유에서도 보낸 값은 버리지 않는다.
		Long otherVideoId = seedVideo(ownerId);
		Report withSpam = reportRepository.findById(
				reportService.report(reporterId, otherVideoId, new ReportCreateRequestDto("SPAM", "같은 광고 반복"))
					.reportId())
			.orElseThrow();
		assertThat(withSpam.getDetail()).isEqualTo("같은 광고 반복");
	}

	@Test
	@DisplayName("지원하지 않는 reason 은 11400 으로 거부된다 (FR-5)")
	void 지원하지_않는_reason은_11400으로_거부된다() {
		assertThatThrownBy(() -> report("BOGUS", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.INVALID_REASON);
		assertThat(reportCount()).isZero();
	}

	@Test
	@DisplayName("OTHER 사유에 상세 설명이 없으면 11401 로 거부된다 — null 과 공백 둘 다 (FR-5a)")
	void OTHER_사유에_상세_설명이_없으면_11401로_거부된다() {
		assertThatThrownBy(() -> report("OTHER", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.DETAIL_REQUIRED);
		assertThatThrownBy(() -> report("OTHER", "   "))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.DETAIL_REQUIRED);
		assertThat(reportCount()).isZero();
	}

	@Test
	@DisplayName("상세 설명 500자 초과는 400 이다 — Bean Validation (FR-5a)")
	void 상세_설명_500자_초과는_400으로_거부된다() throws Exception {
		String tooLong = "가".repeat(501);

		mockMvc.perform(post(URL, videoId)
				.header(HttpHeaders.AUTHORIZATION,
					"Bearer " + tokenProvider.issueAccessToken(reporterId, UserRole.USER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"SPAM\",\"detail\":\"" + tooLong + "\"}"))
			.andExpect(status().isBadRequest());

		assertThat(reportCount()).isZero();
	}

	@Test
	@DisplayName("존재하지 않는 영상 신고는 404 다 (FR-3)")
	void 존재하지_않는_영상_신고는_404다() {
		assertThatThrownBy(() -> reportService.report(reporterId, videoId + 999_999L,
			new ReportCreateRequestDto("SPAM", null)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	@DisplayName("DELETED 영상 신고는 404 다 — 재생 경로와 같은 존재 은닉 (FR-3)")
	void DELETED_영상_신고는_404다() {
		setVideoStatus("DELETED");

		assertThatThrownBy(() -> report("SPAM", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	@DisplayName("BLINDED 영상 신고는 404 다 — 소유자 여부와 무관 (FR-3)")
	void BLINDED_영상_신고는_404다() {
		setVideoStatus("BLINDED");

		assertThatThrownBy(() -> report("SPAM", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	@DisplayName("자기 영상 신고는 11402 로 거부된다 (FR-4)")
	void 자기_영상_신고는_11402로_거부된다() {
		assertThatThrownBy(() -> reportService.report(ownerId, videoId, new ReportCreateRequestDto("SPAM", null)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.SELF_REPORT);
	}

	@Test
	@DisplayName("같은 영상 재신고는 11409 로 거부되고 행이 늘지 않는다 (FR-2)")
	void 같은_영상_재신고는_11409로_거부되고_행이_늘지_않는다() {
		report("SPAM", null);

		assertThatThrownBy(() -> report("INAPPROPRIATE", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.DUPLICATE_REPORT);
		assertThat(reportCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("처리 완료된 신고가 있어도 재신고는 거부된다 — 유니크는 status 무관 (§D3)")
	void 처리_완료된_신고가_있어도_재신고는_거부된다() {
		Long reportId = report("SPAM", null).reportId();
		em.createNativeQuery("UPDATE reports SET status = 'RESOLVED' WHERE id = :i")
			.setParameter("i", reportId)
			.executeUpdate();
		em.clear();

		assertThatThrownBy(() -> report("SPAM", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.DUPLICATE_REPORT);
		assertThat(reportCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("동일 신고 2행 저장은 유니크 제약이 막는다 — V27 백스톱 (§D2)")
	void 동일_신고_2행_저장은_유니크_제약이_막는다() {
		// 동시 요청 레이스를 리포지토리 직접 INSERT 로 재현 — 서비스의 exists 검사를 우회해도 DB 가 막는다.
		// 서비스의 11409 변환은 위 재신고 테스트가 커버한다.
		reportRepository.saveAndFlush(Report.create(reporterId, videoId, ReportReason.SPAM, null));

		assertThatThrownBy(() -> reportRepository.saveAndFlush(
			Report.create(reporterId, videoId, ReportReason.PRIVACY, null)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("신고 접수는 영상 상태를 바꾸지 않는다 — 자동 블라인드 없음 (확정 2번)")
	void 신고_접수는_영상_상태를_바꾸지_않는다() {
		report("INAPPROPRIATE", null);

		assertThat(videoStatus()).isEqualTo("ACTIVE");
	}
}

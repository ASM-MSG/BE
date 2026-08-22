package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.EventTestFixtures;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventVideo;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoCommentRepository;
import com.msg.fillmap.event.repository.EventVideoHelpfulRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 댓글·도움돼요 잠금 배선 (MSG-441 §도메인 로직 "잠금 가드 배선", 고정 Clock 통합). MSG-442 가 정의만 해 둔
 * {@code EventLifecycleGuard.checkInteractionOpen} 의 유일한 소비처가 이 티켓의 변경 API 다섯이라, 여기서
 * 보는 것은 "다섯 경로 전부에 걸렸는가"와 "조회 경로에는 안 걸렸는가" 둘이다 (FR-14).
 * <p>
 * 판정 순서도 계약이다 — 가드가 소유자 검증보다 앞이라, 아카이브된 행사에서는 자기 댓글이든 남의 댓글이든
 * 같은 13422 다. 순서가 반대면 잠금이 아니라 권한 문제로 읽힌다.
 * <p>
 * 잠금 경계는 2026-08-21 에 종료 정각에서 <b>아카이브 전환 정각(종료 + 30일)</b>으로 뒤집혔다(정민 확정).
 * 유예 기간에 올라온 영상이 처음부터 반응을 못 받으면 "행사 다녀와서 나중에 올리기"라는 유예의 목적과
 * 결과가 서로 깎이기 때문이고, 이제 업로드 창과 반응 창이 같은 구간이라 두 규칙이 한 정각에서 닫힌다.
 * <p>
 * 격리(공유 로컬 DB): 서해 먼바다 격자(126.0)와 합성 자연키만 쓰고 {@code @Transactional} 롤백으로 정리한다.
 */
@SpringBootTest
@Transactional
@DisplayName("행사 댓글·도움돼요 잠금 (실 PostgreSQL)")
class EventInteractionLockTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	/** 서해 먼바다 기준 격자 — 다른 행사 테스트(125.0~125.9)와 겹치지 않는 대역. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 126.0));

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventLocationRepository locationRepository;

	@Autowired
	private EventLocationGridRepository locationGridRepository;

	@Autowired
	private EventVideoRepository eventVideoRepository;

	@Autowired
	private EventVideoCommentRepository commentRepository;

	@Autowired
	private EventVideoHelpfulRepository helpfulRepository;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private EventTestFixtures fixtures;
	private Long userId;

	@BeforeEach
	void setUp() {
		fixtures = new EventTestFixtures(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository);
		userId = 사용자();
	}

	private Long 사용자() {
		return userRepository.save(User.createLocalUser(
			"msg441-lock-" + UUID.randomUUID() + "@example.com", "hash", "잠금테스터")).getId();
	}

	private EventVideoInteractionService service(LocalDateTime now) {
		return new EventVideoInteractionServiceImpl(eventVideoRepository, commentRepository, helpfulRepository,
			videoRepository, Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	/** 지정한 일정의 행사에 붙은 노출 영상 하나. */
	private Video 영상(LocalDateTime startsAt, LocalDateTime endsAt, long dy) {
		String gridId = (바다.gridY() + dy) + "_" + 바다.gridX();
		EventOccurrence occurrence = fixtures.회차(fixtures.시리즈(), startsAt, endsAt, gridId);
		EventLocation location = fixtures.위치(occurrence, "행사 위치", gridId);
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		Video video = videoRepository.save(Video.create(userId, gridId,
			"videos/original/" + UUID.randomUUID() + ".mp4", GeoSupport.toPoint(center.lat(), center.lon()),
			(short) 10, startsAt, Visibility.PUBLIC));
		video.markReady("videos/encoded/" + UUID.randomUUID() + ".mp4", "thumb/" + UUID.randomUUID() + ".jpg");
		eventVideoRepository.save(new EventVideo(video, location, occurrence.getId()));
		em.flush();
		return video;
	}

	private void 잠김(ThrowingCallable 호출) {
		assertThatThrownBy(호출)
			.isInstanceOf(ApiException.class)
			.extracting(e -> ((ApiException) e).getErrorCode())
			.isEqualTo(EventErrorCode.EVENT_INTERACTION_LOCKED);
	}

	/** NOW 가 정확히 아카이브 전환 정각이 되는 회차 종료 시각 — 마감 = 종료 + 30일이라 그만큼 앞선다. */
	private LocalDateTime 아카이브전환이_지금인_종료시각() {
		return NOW.minusDays(EventOccurrence.UPLOAD_GRACE_DAYS);
	}

	// 검증: FR-EVENT-10
	@Test
	@DisplayName("아카이브 전환 정각부터 댓글 작성과 수정과 삭제가 13422로 거절된다")
	void 아카이브_전환_정각부터_댓글_작성과_수정과_삭제가_13422로_거절된다() {
		LocalDateTime 종료 = 아카이브전환이_지금인_종료시각();
		Video video = 영상(종료.minusDays(2), 종료, 0);   // 마감 정각(종료 + 30일)이 지금이다
		long commentId = service(NOW.minusDays(1)).createComment(userId, video.getId(), "유예 중 댓글").commentId();

		잠김(() -> service(NOW).createComment(userId, video.getId(), "아카이브 후 댓글"));
		잠김(() -> service(NOW).updateComment(userId, video.getId(), commentId, "아카이브 후 수정"));
		잠김(() -> service(NOW).deleteComment(userId, video.getId(), commentId));
		// 마감 1초 전이면 아직 열려 있다 — 경계는 마감 정각 포함의 반개구간이다 (statusAt 정본).
		assertThatCode(() -> service(NOW.minusSeconds(1)).createComment(userId, video.getId(), "직전 댓글"))
			.doesNotThrowAnyException();
	}

	// 검증: FR-EVENT-10
	@Test
	@DisplayName("아카이브 전환 정각부터 도움돼요 추가와 취소가 13422로 거절된다")
	void 아카이브_전환_정각부터_도움돼요_추가와_취소가_13422로_거절된다() {
		LocalDateTime 종료 = 아카이브전환이_지금인_종료시각();
		Video video = 영상(종료.minusDays(2), 종료, 1);

		잠김(() -> service(NOW).addHelpful(userId, video.getId()));
		잠김(() -> service(NOW).removeHelpful(userId, video.getId()));
	}

	@Test
	@DisplayName("아카이브 이후에도 같은 다섯 요청이 13422다")
	void 아카이브_이후에도_같은_다섯_요청이_13422다() {
		Video video = 영상(NOW.minusDays(2), NOW, 2);
		long commentId = service(NOW.minusHours(1)).createComment(userId, video.getId(), "종료 전 댓글").commentId();
		// 업로드 마감(종료 + 30일)을 하루 넘긴 시점 — 전환 정각뿐 아니라 그 뒤로도 계속 잠겨 있어야 한다.
		LocalDateTime 아카이브 = NOW.plusDays(EventOccurrence.UPLOAD_GRACE_DAYS + 1);

		잠김(() -> service(아카이브).createComment(userId, video.getId(), "댓글"));
		잠김(() -> service(아카이브).updateComment(userId, video.getId(), commentId, "수정"));
		잠김(() -> service(아카이브).deleteComment(userId, video.getId(), commentId));
		잠김(() -> service(아카이브).addHelpful(userId, video.getId()));
		잠김(() -> service(아카이브).removeHelpful(userId, video.getId()));
	}

	@Test
	@DisplayName("예정과 진행 중과 유예 기간 상태에서는 다섯 요청이 모두 성공한다")
	void 예정과_진행_중과_유예_기간_상태에서는_다섯_요청이_모두_성공한다() {
		// 예정 상태도 열려 있다 (PRD §4.2). 노출은 시작 14일 전부터라 이 회차는 이미 보인다.
		Video 예정 = 영상(NOW.plusDays(1), NOW.plusDays(2), 3);
		Video 진행중 = 영상(NOW.minusDays(1), NOW.plusDays(1), 4);
		// 유예 기간(종료 ~ 종료 + 30일)도 2026-08-21 번복으로 열려 있다.
		Video 유예중 = 영상(NOW.minusDays(10), NOW.minusDays(1), 8);

		for (Video video : new Video[] {예정, 진행중, 유예중}) {
			assertThatCode(() -> {
				long commentId = service(NOW).createComment(userId, video.getId(), "댓글").commentId();
				service(NOW).updateComment(userId, video.getId(), commentId, "수정");
				service(NOW).deleteComment(userId, video.getId(), commentId);
				service(NOW).addHelpful(userId, video.getId());
				service(NOW).removeHelpful(userId, video.getId());
			}).doesNotThrowAnyException();
		}
	}

	// 검증: FR-EVENT-10
	@Test
	@DisplayName("유예 기간에 올라온 영상도 댓글과 도움돼요가 열려 있다")
	void 유예_기간에_올라온_영상도_댓글과_도움돼요가_열려_있다() {
		// 종료 이후 30일 안이라 영상을 더 받는 구간이고, 그 영상에 반응도 붙는다 (US-009, 2026-08-21 번복).
		// 유예를 둔 목적이 "행사 다녀와서 나중에 올리기"인데 그 영상이 반응을 못 받으면 목적과 결과가 깎인다.
		Video video = 영상(NOW.minusDays(10), NOW.minusDays(1), 5);

		assertThatCode(() -> {
			service(NOW).createComment(userId, video.getId(), "유예 중 댓글");
			service(NOW).addHelpful(userId, video.getId());
		}).doesNotThrowAnyException();
	}

	// 검증: FR-EVENT-10
	@Test
	@DisplayName("아카이브 후에도 댓글 목록과 두 집계는 그대로 조회된다")
	void 아카이브_후에도_댓글_목록과_두_집계는_그대로_조회된다() {
		Video video = 영상(NOW.minusDays(2), NOW, 6);
		service(NOW.minusHours(1)).createComment(userId, video.getId(), "종료 전 댓글");
		service(NOW.minusHours(1)).addHelpful(userId, video.getId());
		LocalDateTime 아카이브 = NOW.plusDays(EventOccurrence.UPLOAD_GRACE_DAYS + 1);

		// 가드에 조회용 메서드가 아예 없는 것이 FR-14 의 구조적 보장이다.
		assertThat(service(아카이브).getComments(video.getId(), null, 0).comments()).hasSize(1);
		EventVideoDetailReactions reactions = service(아카이브).getDetailReactions(video.getId(), userId);
		assertThat(reactions.commentCount()).isEqualTo(1);
		assertThat(reactions.helpfulCount()).isEqualTo(1);
		assertThat(reactions.helpfulByMe()).isTrue();
	}

	@Test
	@DisplayName("아카이브된 행사에서는 남의 댓글 수정도 자기 댓글 수정도 모두 13422다")
	void 아카이브된_행사에서는_남의_댓글_수정도_자기_댓글_수정도_모두_13422다() {
		LocalDateTime 종료 = 아카이브전환이_지금인_종료시각();
		Video video = 영상(종료.minusDays(2), 종료, 7);
		Long 남 = 사용자();
		long 내댓글 = service(NOW.minusDays(1)).createComment(userId, video.getId(), "내 댓글").commentId();
		long 남의댓글 = service(NOW.minusDays(1)).createComment(남, video.getId(), "남의 댓글").commentId();

		// 가드가 소유자 검증보다 앞이라 두 응답이 같다 — 순서가 반대면 남의 댓글만 13403 으로 갈린다.
		잠김(() -> service(NOW).updateComment(userId, video.getId(), 내댓글, "수정"));
		잠김(() -> service(NOW).updateComment(userId, video.getId(), 남의댓글, "수정"));
		잠김(() -> service(NOW).deleteComment(userId, video.getId(), 남의댓글));
	}
}

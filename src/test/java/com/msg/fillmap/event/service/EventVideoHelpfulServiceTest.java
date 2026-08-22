package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.EventTestFixtures;
import com.msg.fillmap.event.dto.EventVideoHelpfulResponseDto;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventVideo;
import com.msg.fillmap.event.entity.EventVideoHelpfulId;
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
import com.msg.fillmap.video.service.VideoService;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

/**
 * 행사 영상 도움돼요 (MSG-441 §API 4·5, 실 PostgreSQL). 사용자당 1회는 복합 PK 가, 멱등은 native 1문장이
 * 보장하므로 검증 대상이 전부 DB 쪽이다. 동시 요청 두 건은 실제 커밋이 필요해 형제 클래스
 * {@code EventVideoHelpfulConcurrencyTest} 가 맡는다 (MSG-440 이 업로드에서 나눈 것과 같은 구조).
 * <p>
 * 격리(공유 로컬 DB): 서해 먼바다 격자(125.8)와 합성 자연키만 쓰고 {@code @Transactional} 롤백으로 정리한다.
 */
@SpringBootTest
@Transactional
@DisplayName("행사 영상 도움돼요 (실 PostgreSQL)")
class EventVideoHelpfulServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	/** 서해 먼바다 기준 격자 — 다른 행사 테스트(125.0~125.7)와 겹치지 않는 대역. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.8));

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
	private VideoService videoService;

	@Autowired
	private ZoneNameQueryService zoneNameQueryService;

	@Autowired
	private EntityManager em;

	/** presign 은 자격증명이 필요한 로컬 서명이라 목으로 고정한다 (EventVideoQueryServiceTest 선례). */
	@MockitoBean
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	private EventTestFixtures fixtures;
	private Long userId;

	@BeforeEach
	void setUp() {
		given(thumbnailUrlPresigner.presign(anyString())).willReturn("https://example.test/presigned");
		fixtures = new EventTestFixtures(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository);
		userId = 사용자();
	}

	private Long 사용자() {
		return userRepository.save(User.createLocalUser(
			"msg441-helpful-" + UUID.randomUUID() + "@example.com", "hash", "반응자")).getId();
	}

	private EventVideoInteractionService service() {
		return new EventVideoInteractionServiceImpl(eventVideoRepository, commentRepository, helpfulRepository,
			videoRepository, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	private EventLocation 위치(long dy) {
		String gridId = (바다.gridY() + dy) + "_" + 바다.gridX();
		return fixtures.위치(fixtures.회차(fixtures.시리즈(), NOW.minusDays(1), NOW.plusDays(1), gridId), "행사 위치",
			gridId);
	}

	private Video 노출영상(EventLocation location) {
		String gridId = location.getRepresentativeGridId();
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		Video video = videoRepository.save(Video.create(userId, gridId,
			"videos/original/" + UUID.randomUUID() + ".mp4", GeoSupport.toPoint(center.lat(), center.lon()),
			(short) 10, NOW, Visibility.PUBLIC));
		video.markReady("videos/encoded/" + UUID.randomUUID() + ".mp4", "thumb/" + UUID.randomUUID() + ".jpg");
		eventVideoRepository.save(new EventVideo(video, location, location.getOccurrence().getId()));
		em.flush();
		return video;
	}

	// 검증: FR-EVENT-09
	@Test
	@DisplayName("도움돼요 추가는 수를 1 올리고 같은 사용자의 재요청에도 1이다")
	void 도움돼요_추가는_수를_1_올리고_같은_사용자의_재요청에도_1이다() {
		Video video = 노출영상(위치(0));

		EventVideoHelpfulResponseDto 처음 = service().addHelpful(userId, video.getId());
		EventVideoHelpfulResponseDto 재요청 = service().addHelpful(userId, video.getId());

		assertThat(처음.helpfulCount()).isEqualTo(1);
		assertThat(처음.helpfulByMe()).isTrue();
		assertThat(재요청.helpfulCount()).isEqualTo(1);
		assertThat(재요청.helpfulByMe()).isTrue();
	}

	@Test
	@DisplayName("도움돼요 취소는 수를 되돌리고 누른 적 없어도 성공한다")
	void 도움돼요_취소는_수를_되돌리고_누른_적_없어도_성공한다() {
		Video video = 노출영상(위치(1));

		EventVideoHelpfulResponseDto 안누른_취소 = service().removeHelpful(userId, video.getId());
		assertThat(안누른_취소.helpfulCount()).isZero();
		assertThat(안누른_취소.helpfulByMe()).isFalse();

		service().addHelpful(userId, video.getId());
		EventVideoHelpfulResponseDto 취소 = service().removeHelpful(userId, video.getId());
		assertThat(취소.helpfulCount()).isZero();
		assertThat(취소.helpfulByMe()).isFalse();
	}

	@Test
	@DisplayName("서로 다른 사용자의 도움돼요는 각각 집계된다")
	void 서로_다른_사용자의_도움돼요는_각각_집계된다() {
		Video video = 노출영상(위치(2));
		Long 남 = 사용자();

		service().addHelpful(userId, video.getId());
		EventVideoHelpfulResponseDto 남의응답 = service().addHelpful(남, video.getId());

		assertThat(남의응답.helpfulCount()).isEqualTo(2);
		// 취소는 자기 행만 지운다 — 남의 행은 그대로다.
		assertThat(service().removeHelpful(남, video.getId()).helpfulCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("도움돼요 복합키는 같은 값이면 동등하다")
	void 도움돼요_복합키는_같은_값이면_동등하다() {
		EventVideoHelpfulId 하나 = new EventVideoHelpfulId(1042L, 7007L);
		EventVideoHelpfulId 같은값 = new EventVideoHelpfulId(1042L, 7007L);

		// existsById(helpfulByMe 판정)가 이 계약 위에 선다 — 깨지면 항상 미조회로 흘러 화면이 틀린다.
		assertThat(하나).isEqualTo(같은값).hasSameHashCodeAs(같은값);
		assertThat(하나).isNotEqualTo(new EventVideoHelpfulId(1042L, 7008L));
		assertThat(하나).isNotEqualTo(new EventVideoHelpfulId(1043L, 7007L));
	}

	@Test
	@DisplayName("사용자가 탈퇴하면 그 사용자의 도움돼요도 사라진다")
	void 사용자가_탈퇴하면_그_사용자의_도움돼요도_사라진다() {
		Video video = 노출영상(위치(3));
		Long 남 = 사용자();
		service().addHelpful(userId, video.getId());
		service().addHelpful(남, video.getId());
		em.flush();

		// 탈퇴는 하드 삭제라 event_video_helpfuls.user_id FK CASCADE 가 실제로 돈다.
		em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", 남).executeUpdate();
		em.clear();

		assertThat(helpfulRepository.countById_VideoId(video.getId())).isEqualTo(1);
		assertThat(helpfulRepository.existsById(new EventVideoHelpfulId(video.getId(), 남))).isFalse();
	}

	@Nested
	@DisplayName("소프트 삭제된 영상")
	class 소프트삭제 {

		@Test
		@DisplayName("반응 행이 남아도 모든 경로에서 13406이다")
		void 반응_행이_남아도_모든_경로에서_13406이다() {
			EventLocation location = 위치(4);
			Video video = 노출영상(location);
			long videoId = video.getId();
			service().addHelpful(userId, videoId);
			service().createComment(userId, videoId, "삭제 전 댓글");
			em.flush();

			videoService.deleteVideo(userId, videoId);
			em.flush();
			em.clear();

			// 소프트 삭제라 반응 행은 그대로 남는다 — 정리 트랜잭션을 두지 않는다는 결정의 확인이다.
			assertThat(helpfulRepository.countById_VideoId(videoId)).isEqualTo(1);
			assertThat(commentRepository.countByVideo_VideoId(videoId)).isEqualTo(1);

			// 남은 행이 응답에 새는 경로가 없다 — 조회·변경이 전부 노출 술어에서 막힌다.
			은닉됨(() -> 영상서비스().getVideoDetail(videoId, userId));
			assertThat(피드영상수(location)).isZero();
			은닉됨(() -> service().getComments(videoId, null, 0));
			은닉됨(() -> service().createComment(userId, videoId, "댓글"));
			은닉됨(() -> service().addHelpful(userId, videoId));
			은닉됨(() -> service().removeHelpful(userId, videoId));
		}

		private int 피드영상수(EventLocation location) {
			return 영상서비스()
				.getLocationVideos(location.getOccurrence().getId(), location.getId(), null, 0)
				.videos()
				.size();
		}

		private EventVideoService 영상서비스() {
			return new EventVideoServiceImpl(occurrenceRepository, locationRepository, eventVideoRepository,
				videoService, videoRepository, thumbnailUrlPresigner, zoneNameQueryService, em, service(),
				Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
		}

		private void 은닉됨(ThrowingCallable 호출) {
			assertThatThrownBy(호출)
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_VIDEO_NOT_FOUND);
		}
	}
}

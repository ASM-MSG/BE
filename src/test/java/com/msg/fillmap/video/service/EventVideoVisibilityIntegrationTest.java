package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;

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
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoVisibilityRequestDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 행사 영상 공개범위 고정 (MSG-440 §기존 API 변경 1). 행사 영상은 PUBLIC 전제라 전환 API 가 거부하고,
 * 같은 API 가 일반 영상에는 그대로 동작해야 한다 — 차단이 event_videos 행 유무에만 걸린다는 확인이다.
 * <p>
 * 격리(공유 로컬 DB): 서해 먼바다 격자와 합성 자연키만 쓰고 {@code @Transactional} 롤백으로 정리한다.
 */
@SpringBootTest
@Transactional
@DisplayName("행사 영상 공개범위 전환 차단 (실 PostgreSQL)")
class EventVideoVisibilityIntegrationTest {

	/** 서해 먼바다 기준 격자 — 육상 실데이터와 겹치지 않는다. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.2));

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	@Autowired
	private VideoService videoService;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EventVideoRepository eventVideoRepository;

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventLocationRepository locationRepository;

	@Autowired
	private EventLocationGridRepository locationGridRepository;

	private EventTestFixtures fixtures;
	private Long userId;

	@BeforeEach
	void setUp() {
		fixtures = new EventTestFixtures(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository);
		userId = userRepository.save(User.createLocalUser(
			"msg440-vis-" + UUID.randomUUID() + "@example.com", "hash", "행사업로더")).getId();
	}

	private String 격자(long dx) {
		return 바다.gridY() + "_" + (바다.gridX() + dx);
	}

	private Video 영상(String gridId) {
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		return videoRepository.save(Video.create(userId, gridId, "videos/original/" + UUID.randomUUID() + ".mp4",
			GeoSupport.toPoint(center.lat(), center.lon()), (short) 10, NOW, Visibility.PUBLIC));
	}

	// 검증: FR-EVENT-09
	@Test
	@DisplayName("행사 영상의 공개범위 전환은 3427로 거절된다")
	void 행사_영상의_공개범위_전환은_3427로_거절된다() {
		String gridId = 격자(0);
		EventOccurrence occurrence = fixtures.회차(fixtures.시리즈(), NOW.minusDays(1), NOW.plusDays(1), gridId);
		EventLocation location = fixtures.위치(occurrence, "행사 위치", gridId);
		Video video = 영상(gridId);
		eventVideoRepository.save(new EventVideo(video, location, occurrence.getId()));

		assertThatThrownBy(() -> videoService.setVisibility(
			userId, video.getId(), new VideoVisibilityRequestDto("PRIVATE")))
			.isInstanceOf(ApiException.class)
			.extracting(e -> ((ApiException) e).getErrorCode())
			.isEqualTo(VideoErrorCode.EVENT_VIDEO_VISIBILITY_FIXED);
		assertThat(video.getVisibility()).isEqualTo(Visibility.PUBLIC);
	}

	@Test
	@DisplayName("일반 영상의 공개범위 전환은 기존대로 동작한다")
	void 일반_영상의_공개범위_전환은_기존대로_동작한다() {
		Video video = 영상(격자(1));

		videoService.setVisibility(userId, video.getId(), new VideoVisibilityRequestDto("PRIVATE"));

		assertThat(video.getVisibility()).isEqualTo(Visibility.PRIVATE);
	}
}

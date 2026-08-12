package com.msg.fillmap.video.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.support.GeoSupport;

@SpringBootTest
@Transactional
@DisplayName("VideoRepository 격자별 내 영상 조회 (실 PostGIS)")
class VideoGridQueryTest {

	private static final double 성수_LAT = 37.5445;
	private static final double 성수_LON = 127.0560;
	private static final double 강남_LAT = 37.4979;
	private static final double 강남_LON = 127.0276;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private Long userId;
	private String 성수_GRID;
	private String 강남_GRID;

	@BeforeEach
	void setUp() {
		userId = userRepository.save(User.createLocalUser("grid-query@example.com", "hash", "테스터")).getId();
		성수_GRID = registerGrid(성수_LAT, 성수_LON);
		강남_GRID = registerGrid(강남_LAT, 강남_LON);
	}

	private String registerGrid(double lat, double lon) {
		String gridId = GridEncoder.encode(lat, lon);
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(
			gridId, index.gridY(), index.gridX(), center.lat(), center.lon(), GeoSupport.bboxWkt(gridId));
		return gridId;
	}

	private Long saveVideo(Long ownerId, String gridId, double lat, double lon, VideoStatus status) {
		Point geom = GeoSupport.toPoint(lat, lon);
		String key = "videos/original/" + java.util.UUID.randomUUID() + ".mp4";   // uq_videos_original_s3_key
		Video video = Video.create(ownerId, gridId, key, geom, (short) 10, LocalDateTime.now(), Visibility.PRIVATE);
		if (status == VideoStatus.DELETED) {
			video.markDeleted();
		}
		return videoRepository.save(video).getId();
	}

	/** created_at 은 생성자에서 채워져 세 건이 같은 시각일 수 있어, 정렬 검증용으로 명시적으로 벌린다. */
	private void setCreatedAt(Long videoId, LocalDateTime ts) {
		em.createNativeQuery("UPDATE videos SET created_at = :ts WHERE id = :id")
			.setParameter("ts", ts)
			.setParameter("id", videoId)
			.executeUpdate();
	}

	private List<Video> query(String gridId) {
		em.flush();
		em.clear();
		return videoRepository.findByUserIdAndGridIdAndStatusOrderByCreatedAtDesc(userId, gridId, VideoStatus.ACTIVE);
	}

	// 검증: FR-COLLECT-11
	@Test
	@DisplayName("내가 그 격자에 올린 영상만 createdAt 내림차순으로 반환한다")
	void 내가_그_격자에_올린_영상만_createdAt_내림차순으로_반환한다() {
		Long oldest = saveVideo(userId, 성수_GRID, 성수_LAT, 성수_LON, VideoStatus.ACTIVE);
		Long middle = saveVideo(userId, 성수_GRID, 성수_LAT, 성수_LON, VideoStatus.ACTIVE);
		Long newest = saveVideo(userId, 성수_GRID, 성수_LAT, 성수_LON, VideoStatus.ACTIVE);
		setCreatedAt(oldest, LocalDateTime.of(2026, 7, 20, 10, 0, 0));
		setCreatedAt(middle, LocalDateTime.of(2026, 7, 20, 11, 0, 0));
		setCreatedAt(newest, LocalDateTime.of(2026, 7, 20, 12, 0, 0));

		List<Video> result = query(성수_GRID);

		assertThat(result).extracting(Video::getId).containsExactly(newest, middle, oldest);
	}

	@Test
	@DisplayName("삭제된 영상은 조회결과에 포함되지 않는다")
	void 삭제된_영상은_조회결과에_포함되지_않는다() {
		Long active = saveVideo(userId, 성수_GRID, 성수_LAT, 성수_LON, VideoStatus.ACTIVE);
		saveVideo(userId, 성수_GRID, 성수_LAT, 성수_LON, VideoStatus.DELETED);

		List<Video> result = query(성수_GRID);

		assertThat(result).extracting(Video::getId).containsExactly(active);
	}

	@Test
	@DisplayName("다른 사용자의 영상은 내 조회결과에 포함되지 않는다")
	void 다른_사용자의_영상은_내_조회결과에_포함되지_않는다() {
		Long otherUserId = userRepository.save(User.createLocalUser("other@example.com", "hash", "타인")).getId();
		Long mine = saveVideo(userId, 성수_GRID, 성수_LAT, 성수_LON, VideoStatus.ACTIVE);
		saveVideo(otherUserId, 성수_GRID, 성수_LAT, 성수_LON, VideoStatus.ACTIVE);

		List<Video> result = query(성수_GRID);

		assertThat(result).extracting(Video::getId).containsExactly(mine);
	}

	@Test
	@DisplayName("다른 격자의 영상은 조회결과에 포함되지 않는다")
	void 다른_격자의_영상은_조회결과에_포함되지_않는다() {
		Long here = saveVideo(userId, 성수_GRID, 성수_LAT, 성수_LON, VideoStatus.ACTIVE);
		saveVideo(userId, 강남_GRID, 강남_LAT, 강남_LON, VideoStatus.ACTIVE);

		List<Video> result = query(성수_GRID);

		assertThat(result).extracting(Video::getId).containsExactly(here);
	}

	@Test
	@DisplayName("영상이 없는 격자를 조회하면 빈 리스트를 반환한다")
	void 영상이_없는_격자를_조회하면_빈_리스트를_반환한다() {
		List<Video> result = query(강남_GRID);

		assertThat(result).isEmpty();
	}
}

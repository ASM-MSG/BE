package com.msg.fillmap.video.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Optional;

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
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 전역 대표 선정(조회수 → 최신, PUBLIC·READY·ACTIVE) 을 실 PostGIS 로 검증한다 (MSG-87).
 * 엔티티에 visibility·processing_status·view_count 세터가 없어(불변) 저장 후 native UPDATE 로 상태를 벌린다 —
 * created_at 을 native 로 벌리는 VideoGridQueryTest 선례와 같은 방식이다.
 */
@SpringBootTest
@Transactional
@DisplayName("VideoRepository 격자 전역 대표 조회 (실 PostGIS)")
class VideoGlobalCoverQueryTest {

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
		userId = userRepository.save(User.createLocalUser("global-cover@example.com", "hash", "테스터")).getId();
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

	/** 기본(PRIVATE·UPLOADED·view_count 0)으로 저장한 뒤, native UPDATE 로 대표 후보 조건을 명시적으로 맞춘다. */
	private Long saveVideo(Long ownerId, String gridId, String visibility, String processingStatus,
		VideoStatus status, long viewCount, LocalDateTime createdAt) {
		Point geom = GeoSupport.toPoint(성수_LAT, 성수_LON);
		String key = "videos/original/" + java.util.UUID.randomUUID() + ".mp4";   // uq_videos_original_s3_key
		Video video = Video.create(ownerId, gridId, key, geom, (short) 10, LocalDateTime.now());
		if (status == VideoStatus.DELETED) {
			video.markDeleted();
		}
		Long id = videoRepository.save(video).getId();
		em.createNativeQuery("UPDATE videos SET visibility = :vis, processing_status = :ps, "
				+ "view_count = :vc, created_at = :ts WHERE id = :id")
			.setParameter("vis", visibility)
			.setParameter("ps", processingStatus)
			.setParameter("vc", viewCount)
			.setParameter("ts", createdAt)
			.setParameter("id", id)
			.executeUpdate();
		return id;
	}

	/** 대표 후보 조건을 모두 만족하는(PUBLIC·READY·ACTIVE) 영상. */
	private Long publicReady(Long ownerId, String gridId, long viewCount, LocalDateTime createdAt) {
		return saveVideo(ownerId, gridId, "PUBLIC", "READY", VideoStatus.ACTIVE, viewCount, createdAt);
	}

	private Optional<Video> cover(String gridId) {
		em.flush();
		em.clear();
		return videoRepository.findGlobalCover(gridId);
	}

	private static LocalDateTime at(int hour) {
		return LocalDateTime.of(2026, 7, 20, hour, 0, 0);
	}

	@Test
	@DisplayName("조회수가 가장 높은 공개 READY 영상이 대표로 선정된다")
	void 조회수가_가장_높은_공개_READY_영상이_대표로_선정된다() {
		publicReady(userId, 성수_GRID, 3L, at(10));
		Long popular = publicReady(userId, 성수_GRID, 99L, at(9));
		publicReady(userId, 성수_GRID, 50L, at(11));

		assertThat(cover(성수_GRID)).map(Video::getId).contains(popular);
	}

	@Test
	@DisplayName("조회수 동률이면 최신 영상이 대표가 된다")
	void 조회수_동률이면_최신_영상이_대표가_된다() {
		publicReady(userId, 성수_GRID, 10L, at(9));
		Long newest = publicReady(userId, 성수_GRID, 10L, at(12));
		publicReady(userId, 성수_GRID, 10L, at(11));

		assertThat(cover(성수_GRID)).map(Video::getId).contains(newest);
	}

	@Test
	@DisplayName("비공개 PRIVATE 영상은 대표가 되지 않는다")
	void 비공개_PRIVATE_영상은_대표가_되지_않는다() {
		saveVideo(userId, 성수_GRID, "PRIVATE", "READY", VideoStatus.ACTIVE, 99L, at(10));

		assertThat(cover(성수_GRID)).isEmpty();
	}

	@Test
	@DisplayName("인코딩중 영상은 대표가 되지 않는다")
	void 인코딩중_영상은_대표가_되지_않는다() {
		saveVideo(userId, 성수_GRID, "PUBLIC", "ENCODING", VideoStatus.ACTIVE, 99L, at(10));

		assertThat(cover(성수_GRID)).isEmpty();
	}

	@Test
	@DisplayName("삭제된 영상은 대표가 되지 않는다")
	void 삭제된_영상은_대표가_되지_않는다() {
		saveVideo(userId, 성수_GRID, "PUBLIC", "READY", VideoStatus.DELETED, 99L, at(10));

		assertThat(cover(성수_GRID)).isEmpty();
	}

	@Test
	@DisplayName("다른 사용자의 공개 영상도 대표가 될 수 있다")
	void 다른_사용자의_공개_영상도_대표가_될_수_있다() {
		Long otherUserId = userRepository.save(User.createLocalUser("other-cover@example.com", "hash", "타인")).getId();
		Long others = publicReady(otherUserId, 성수_GRID, 99L, at(10));
		publicReady(userId, 성수_GRID, 5L, at(11));

		assertThat(cover(성수_GRID)).map(Video::getId).contains(others);
	}

	@Test
	@DisplayName("공개 READY 영상이 없는 격자는 빈 Optional 을 반환한다")
	void 공개_READY_영상이_없는_격자는_빈_Optional을_반환한다() {
		publicReady(userId, 성수_GRID, 99L, at(10));   // 다른 격자에만 후보 존재

		assertThat(cover(강남_GRID)).isEmpty();
	}
}

package com.msg.fillmap.video.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

/**
 * 격자 전역 영상 목록 쿼리(인기순 keyset 페이지, PUBLIC·READY·ACTIVE) 를 실 PostGIS 로 검증한다 (MSG-237).
 * 필터·정렬은 findGlobalCover(MSG-87) 와 같은 후보 집합을 LIMIT 1 → 페이지 윈도우로 넓힌 것이다.
 * 엔티티에 visibility·processing_status·view_count 세터가 없어(불변) 저장 후 native UPDATE 로 상태를 벌린다 —
 * VideoGlobalCoverQueryTest 선례와 같은 방식이다. original_s3_key 는 UUID 로 유니크화한다(MSG-127 선례).
 */
@SpringBootTest
@Transactional
@DisplayName("VideoRepository 격자 전역 영상 목록 조회 (실 PostGIS)")
class VideoGlobalListQueryTest {

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
		userId = userRepository.save(User.createLocalUser("global-list@example.com", "hash", "테스터")).getId();
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

	/** PRIVATE 명시·기본(UPLOADED·view_count 0)으로 저장한 뒤, native UPDATE 로 목록 후보 조건을 명시적으로 맞춘다. */
	private Long saveVideo(Long ownerId, String gridId, String visibility, String processingStatus,
		VideoStatus status, long viewCount, LocalDateTime createdAt) {
		Point geom = GeoSupport.toPoint(성수_LAT, 성수_LON);
		String key = "videos/original/" + UUID.randomUUID() + ".mp4";   // uq_videos_original_s3_key
		Video video = Video.create(ownerId, gridId, key, geom, (short) 10, LocalDateTime.now(), Visibility.PRIVATE);
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

	/** 목록 후보 조건을 모두 만족하는(PUBLIC·READY·ACTIVE) 영상. */
	private Long publicReady(Long ownerId, String gridId, long viewCount, LocalDateTime createdAt) {
		return saveVideo(ownerId, gridId, "PUBLIC", "READY", VideoStatus.ACTIVE, viewCount, createdAt);
	}

	private List<Video> list(String gridId, int limit) {
		em.flush();
		em.clear();
		return videoRepository.findGlobalVideos(gridId, limit);
	}

	private List<Video> listAfter(String gridId, long viewCount, LocalDateTime createdAt, long id, int limit) {
		em.flush();
		em.clear();
		return videoRepository.findGlobalVideosAfter(gridId, viewCount, createdAt, id, limit);
	}

	private static LocalDateTime at(int hour) {
		return LocalDateTime.of(2026, 7, 20, hour, 0, 0);
	}

	@Test
	@DisplayName("공개 READY 영상을 조회수 → 최신순으로 반환한다")
	void 공개_READY_영상을_조회수_최신순으로_반환한다() {
		Long low = publicReady(userId, 성수_GRID, 3L, at(10));
		Long top = publicReady(userId, 성수_GRID, 99L, at(9));
		Long mid = publicReady(userId, 성수_GRID, 50L, at(11));

		assertThat(list(성수_GRID, 20)).extracting(Video::getId).containsExactly(top, mid, low);
	}

	@Test
	@DisplayName("비공개 PRIVATE 영상은 목록에 포함되지 않는다")
	void 비공개_PRIVATE_영상은_목록에_포함되지_않는다() {
		saveVideo(userId, 성수_GRID, "PRIVATE", "READY", VideoStatus.ACTIVE, 99L, at(10));

		assertThat(list(성수_GRID, 20)).isEmpty();
	}

	@Test
	@DisplayName("내 PRIVATE 영상도 전역 목록엔 포함되지 않는다 (§D1 축 구분 — 내 비공개는 my-videos 축)")
	void 내_PRIVATE_영상도_전역_목록엔_포함되지_않는다() {
		// 소유자 분기(OR user_id=:me) 없음 — 본인 영상이라도 PRIVATE 면 전역 목록에서 제외된다(MSG-127 과 반대 축).
		saveVideo(userId, 성수_GRID, "PRIVATE", "READY", VideoStatus.ACTIVE, 99L, at(10));
		Long myPublic = publicReady(userId, 성수_GRID, 1L, at(9));

		assertThat(list(성수_GRID, 20)).extracting(Video::getId).containsExactly(myPublic);
	}

	@Test
	@DisplayName("인코딩중 영상은 목록에 포함되지 않는다 (READY MUST — MSG-162 §도메인3)")
	void 인코딩중_영상은_목록에_포함되지_않는다() {
		saveVideo(userId, 성수_GRID, "PUBLIC", "ENCODING", VideoStatus.ACTIVE, 99L, at(10));

		assertThat(list(성수_GRID, 20)).isEmpty();
	}

	@Test
	@DisplayName("삭제된 영상은 목록에 포함되지 않는다 (status=ACTIVE)")
	void 삭제된_영상은_목록에_포함되지_않는다() {
		saveVideo(userId, 성수_GRID, "PUBLIC", "READY", VideoStatus.DELETED, 99L, at(10));

		assertThat(list(성수_GRID, 20)).isEmpty();
	}

	@Test
	@DisplayName("다른 사용자의 공개 READY 영상도 목록에 포함된다 (전역 선정 — 본인 포함)")
	void 다른_사용자의_공개_READY_영상도_목록에_포함된다() {
		Long otherUserId = userRepository.save(User.createLocalUser("other-list@example.com", "hash", "타인")).getId();
		Long others = publicReady(otherUserId, 성수_GRID, 99L, at(10));
		Long mine = publicReady(userId, 성수_GRID, 5L, at(11));

		assertThat(list(성수_GRID, 20)).extracting(Video::getId).containsExactly(others, mine);
	}

	@Test
	@DisplayName("조회수 동률이면 최신 영상이 먼저다 (created_at DESC 타이브레이크)")
	void 조회수_동률이면_최신_영상이_먼저다() {
		Long oldest = publicReady(userId, 성수_GRID, 10L, at(9));
		Long newest = publicReady(userId, 성수_GRID, 10L, at(12));
		Long middle = publicReady(userId, 성수_GRID, 10L, at(11));

		assertThat(list(성수_GRID, 20)).extracting(Video::getId).containsExactly(newest, middle, oldest);
	}

	@Test
	@DisplayName("조회수와 생성시각이 같으면 id 내림차순으로 결정적이다 (페이지 셔플 방지)")
	void 조회수와_생성시각이_같으면_id_내림차순으로_결정적이다() {
		Long first = publicReady(userId, 성수_GRID, 10L, at(10));
		Long second = publicReady(userId, 성수_GRID, 10L, at(10));
		Long third = publicReady(userId, 성수_GRID, 10L, at(10));

		assertThat(list(성수_GRID, 20)).extracting(Video::getId).containsExactly(third, second, first);
	}

	@Test
	@DisplayName("공개 READY 영상이 없는 격자는 빈 목록이다")
	void 공개_READY_영상이_없는_격자는_빈_목록이다() {
		publicReady(userId, 성수_GRID, 99L, at(10));   // 다른 격자에만 후보 존재

		assertThat(list(강남_GRID, 20)).isEmpty();
	}

	@Test
	@DisplayName("size 만큼 한 페이지를 반환하고 다음이 있으면 lookahead 로 판정한다")
	void size만큼_한_페이지를_반환하고_다음이_있으면_lookahead로_판정한다() {
		Long top = publicReady(userId, 성수_GRID, 30L, at(10));
		Long mid = publicReady(userId, 성수_GRID, 20L, at(10));
		publicReady(userId, 성수_GRID, 10L, at(10));

		int size = 2;
		List<Video> rows = list(성수_GRID, size + 1);   // lookahead: limit = size + 1

		assertThat(rows).hasSize(size + 1);   // size+1 행 → hasNext=true 판정 근거(서비스 소관)
		assertThat(rows.subList(0, size)).extracting(Video::getId).containsExactly(top, mid);
	}

	@Test
	@DisplayName("커서로 다음 페이지가 이어지고 전체 순회가 비페이지 집합과 일치한다 (skip/dup 없음)")
	void 커서로_다음_페이지가_이어지고_전체_순회가_비페이지_집합과_일치한다() {
		// 동률(view_count)·동시각(created_at) 경계가 페이지 사이에 걸리도록 배치해 row-value 커서의 결정성을 함께 본다.
		publicReady(userId, 성수_GRID, 9L, at(10));
		publicReady(userId, 성수_GRID, 7L, at(12));
		publicReady(userId, 성수_GRID, 7L, at(9));
		publicReady(userId, 성수_GRID, 5L, at(11));
		publicReady(userId, 성수_GRID, 5L, at(11));   // created_at 까지 동률 → id DESC 로만 구분
		publicReady(userId, 성수_GRID, 5L, at(8));
		publicReady(userId, 성수_GRID, 1L, at(10));

		List<Long> all = list(성수_GRID, 100).stream().map(Video::getId).toList();
		assertThat(all).hasSize(7);

		int size = 3;
		List<Long> traversed = new ArrayList<>();
		List<Video> rows = list(성수_GRID, size + 1);
		while (true) {
			boolean hasNext = rows.size() > size;
			List<Video> pageRows = hasNext ? rows.subList(0, size) : rows;
			pageRows.forEach(video -> traversed.add(video.getId()));
			if (!hasNext) {
				break;
			}
			Video last = pageRows.get(pageRows.size() - 1);
			rows = listAfter(성수_GRID, last.getViewCount(), last.getCreatedAt(), last.getId(), size + 1);
		}

		assertThat(traversed).containsExactlyElementsOf(all);
	}
}

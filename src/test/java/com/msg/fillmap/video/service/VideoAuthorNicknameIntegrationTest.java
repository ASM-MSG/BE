package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.GridGlobalVideoResponseDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 전역 노출 응답의 작성자 닉네임 (MSG-371)을 실 DB 로 검증한다. 단위 테스트는 리포지토리를 목으로 두므로
 * JPQL 2건이 실제 users 를 읽는지·닉네임이 응답에 복사되지 않고 매 조회 현재 값을 따라가는지(FR-3)는
 * 여기서만 확인된다. presign 은 AWS 자격증명 의존을 피해 mock 으로 대체한다
 * (URL 서명 검증은 VideoGlobalListServiceTest 담당 — VideoGlobalListViewCountIntegrationTest 전략).
 */
@SpringBootTest
@Transactional
@DisplayName("전역 노출 응답 작성자 닉네임 (MSG-371, 실 DB)")
class VideoAuthorNicknameIntegrationTest {

	private static final double 성수_LAT = 37.5445;
	private static final double 성수_LON = 127.0560;

	@Autowired
	private VideoService videoService;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@MockitoBean
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	private Long authorId;
	private Long otherAuthorId;
	private String gridId;

	@BeforeEach
	void setUp() {
		given(thumbnailUrlPresigner.presign(anyString())).willReturn("https://signed");
		given(thumbnailUrlPresigner.ttlSeconds()).willReturn(600L);
		authorId = userRepository.save(User.createLocalUser("nickname-a@example.com", "hash", "부산브이로그")).getId();
		otherAuthorId = userRepository.save(User.createLocalUser("nickname-b@example.com", "hash", "서울산책")).getId();
		gridId = registerGrid();
	}

	private String registerGrid() {
		String id = GridEncoder.encode(성수_LAT, 성수_LON);
		GridIndex index = GridEncoder.decode(id);
		GridPoint center = GridEncoder.center(id);
		videoRepository.upsertGrid(
			id, index.gridY(), index.gridX(), center.lat(), center.lon(), GeoSupport.bboxWkt(id));
		return id;
	}

	/** PUBLIC·READY·ACTIVE 영상 하나 — 전역 대표·목록 후보 조건을 만족한다. */
	private Long publicReady(Long ownerId, long viewCount) {
		Point geom = GeoSupport.toPoint(성수_LAT, 성수_LON);
		String key = "videos/original/" + UUID.randomUUID() + ".mp4";   // uq_videos_original_s3_key
		Long id = videoRepository.save(
			Video.create(ownerId, gridId, key, geom, (short) 10, LocalDateTime.now(), Visibility.PRIVATE)).getId();
		em.createNativeQuery("UPDATE videos SET visibility='PUBLIC', processing_status='READY', status='ACTIVE', "
				+ "encoded_url = :enc, view_count = :vc WHERE id = :id")
			.setParameter("enc", "videos/encoded/" + id + ".mp4")
			.setParameter("vc", viewCount)
			.setParameter("id", id)
			.executeUpdate();
		return id;
	}

	private void flushAndClear() {
		em.flush();
		em.clear();
	}

	@Test
	@DisplayName("전역 목록·대표·재생 응답이 실제 users.nickname 을 싣는다")
	void 전역_노출_응답_3종이_실제_닉네임을_싣는다() {
		Long mine = publicReady(authorId, 10L);
		Long others = publicReady(otherAuthorId, 5L);
		flushAndClear();

		assertThat(videoService.getGridGlobalVideos(gridId, null, 20).videos())
			.extracting(GridGlobalVideoResponseDto::videoId, GridGlobalVideoResponseDto::nickname)
			.containsExactly(tuple(mine, "부산브이로그"), tuple(others, "서울산책"));
		// 대표는 조회수 최상위 1건 — 그 작성자의 닉네임이 실린다.
		assertThat(videoService.getGridCover(gridId).nickname()).isEqualTo("부산브이로그");
		assertThat(videoService.getVideoPlayback(otherAuthorId, mine).nickname()).isEqualTo("부산브이로그");
	}

	@Test
	@DisplayName("작성자가 닉네임을 바꾸면 다음 조회부터 바뀐 닉네임이 나온다")
	void 작성자가_닉네임을_바꾸면_다음_조회부터_바뀐_닉네임이_나온다() {
		Long videoId = publicReady(authorId, 10L);
		flushAndClear();
		assertThat(videoService.getGridCover(gridId).nickname()).isEqualTo("부산브이로그");

		em.createNativeQuery("UPDATE users SET nickname = :n WHERE id = :id")
			.setParameter("n", "부산기록가")
			.setParameter("id", authorId)
			.executeUpdate();
		flushAndClear();

		// 응답에도 videos 행에도 사본이 없으므로 다음 조회가 바뀐 값을 그대로 읽는다 (FR-3).
		assertThat(videoService.getGridCover(gridId).nickname()).isEqualTo("부산기록가");
		assertThat(videoService.getGridGlobalVideos(gridId, null, 20).videos().get(0).nickname())
			.isEqualTo("부산기록가");
		assertThat(videoService.getVideoPlayback(otherAuthorId, videoId).nickname()).isEqualTo("부산기록가");
	}
}

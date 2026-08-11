package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 재생 조회수 증가(MSG-206) ↔ 전역 대표 선정(MSG-87 findGlobalCover view_count DESC) 정합을 실 서비스·실 PostGIS 로
 * 검증한다. 타인이 후순위 영상을 재생해 view_count 가 오르면 그 영상이 대표로 뒤집히는지 본다. presign 은 AWS
 * 자격증명 의존을 피해 mock 으로 대체한다 — 여기서 검증할 축은 URL 서명이 아니라 조회수 증가가 정렬에 반영되는지다
 * (presign 서명 검증은 VideoPlaybackServiceTest 가 실 S3Presigner 로 담당).
 */
@SpringBootTest
@Transactional
@DisplayName("재생 조회수 증가가 전역 대표 선정에 반영된다 (MSG-206 × MSG-87, 실 PostGIS)")
class VideoPlaybackViewCountIntegrationTest {

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

	private Long ownerId;
	private long otherCaller;
	private String gridId;

	@BeforeEach
	void setUp() {
		given(thumbnailUrlPresigner.presign(anyString())).willReturn("https://signed");
		given(thumbnailUrlPresigner.ttlSeconds()).willReturn(600L);
		ownerId = userRepository.save(User.createLocalUser("playback-cover@example.com", "hash", "주인")).getId();
		otherCaller = ownerId + 1;   // 소유자와 다른 caller 면 충분(caller 는 FK 아님)
		gridId = registerGrid(성수_LAT, 성수_LON);
	}

	private String registerGrid(double lat, double lon) {
		String id = GridEncoder.encode(lat, lon);
		GridIndex index = GridEncoder.decode(id);
		GridPoint center = GridEncoder.center(id);
		videoRepository.upsertGrid(
			id, index.gridY(), index.gridX(), center.lat(), center.lon(), GeoSupport.bboxWkt(id));
		return id;
	}

	/** PUBLIC·READY·ACTIVE(encoded_url 세팅) 영상 하나 — 대표 후보 조건을 만족한다. */
	private Long publicReady(long viewCount, LocalDateTime createdAt) {
		Point geom = GeoSupport.toPoint(성수_LAT, 성수_LON);
		String key = "videos/original/" + UUID.randomUUID() + ".mp4";   // uq_videos_original_s3_key
		Long id = videoRepository.save(
			Video.create(ownerId, gridId, key, geom, (short) 10, LocalDateTime.now(), Visibility.PRIVATE)).getId();
		em.createNativeQuery("UPDATE videos SET visibility='PUBLIC', processing_status='READY', status='ACTIVE', "
				+ "encoded_url = :enc, view_count = :vc, created_at = :ts WHERE id = :id")
			.setParameter("enc", "videos/encoded/" + id + ".mp4")
			.setParameter("vc", viewCount)
			.setParameter("ts", createdAt)
			.setParameter("id", id)
			.executeUpdate();
		return id;
	}

	private Long coverId() {
		em.flush();
		em.clear();
		return videoRepository.findGlobalCover(gridId).map(Video::getId).orElse(null);
	}

	private static LocalDateTime at(int hour) {
		return LocalDateTime.of(2026, 7, 20, hour, 0, 0);
	}

	// 검증: FR-VIDEO-14
	@Test
	@DisplayName("타인 재생으로 오른 view_count 가 대표 영상 선정 정렬에 반영된다")
	void 타인_재생으로_오른_view_count가_대표_영상_선정_정렬에_반영된다() {
		Long videoA = publicReady(5L, at(9));    // 오래됨
		Long videoB = publicReady(5L, at(12));   // 최신 — 조회수 동률이라 초기 대표

		assertThat(coverId()).isEqualTo(videoB);

		// 타인이 A 를 재생 → view_count 5 → 6 (원자적 UPDATE)
		videoService.getVideoPlayback(otherCaller, videoA);

		// A(6) > B(5) → 대표가 A 로 뒤집힌다. findGlobalCover 의 view_count DESC 가 증가를 본다.
		assertThat(coverId()).isEqualTo(videoA);
	}
}

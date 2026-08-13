package com.msg.fillmap.video.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 격자 전역 시간대 집계 쿼리(KST 시별 개수, PUBLIC·READY·ACTIVE)를 실 DB 로 검증한다 (MSG-372).
 * 게이트는 findGlobalVideos(MSG-237)와 같은 문자열이라 여기서 제외 케이스를 그대로 다시 본다 —
 * 카드에 보이는 영상과 차트에 세어지는 영상이 갈리면 안 되기 때문이다. 24구간 채움은 서비스 몫이라
 * 이 쿼리는 업로드가 있는 시간대만 돌려준다. 시드는 @Transactional 롤백으로 공유 로컬 DB 에 남지 않는다.
 * 상태를 native UPDATE 로 벌리는 방식은 VideoGlobalListQueryTest 선례다.
 */
@SpringBootTest
@Transactional
@DisplayName("VideoRepository 격자 전역 시간대 집계 조회 (실 DB)")
class VideoHourlyUploadQueryTest {

	// 다른 테스트 좌표 대역과 겹치지 않는 서해 공해상 합성 격자.
	private static final long GRID_Y = 17810L;
	private static final long GRID_X = 7793L;
	private static final String STORED_ZONE = ZoneOffset.UTC.getId();   // 서비스가 바인딩하는 값 그대로

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private Long userId;
	private String targetGridId;
	private String otherGridId;

	@BeforeEach
	void setUp() {
		userId = newUser("테스터");
		targetGridId = GridFixtures.seedGrid(em, GRID_Y, GRID_X);
		otherGridId = GridFixtures.seedGrid(em, GRID_Y, GRID_X + 1);
	}

	private Long newUser(String nickname) {
		String email = "hourly-" + UUID.randomUUID() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", nickname)).getId();
	}

	/** PRIVATE·UPLOADED 로 저장한 뒤 native UPDATE 로 게이트 조건과 created_at 을 명시적으로 맞춘다. */
	private void saveVideo(Long ownerId, String gridId, Visibility visibility, ProcessingStatus processingStatus,
		VideoStatus status, LocalDateTime createdAtUtc) {
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridFixtures.pointAt(index.gridY() + 0.5, index.gridX() + 0.5);
		Point geom = GeoSupport.toPoint(center.lat(), center.lon());
		String key = "videos/original/" + UUID.randomUUID() + ".mp4";   // uq_videos_original_s3_key
		Video video = Video.create(ownerId, gridId, key, geom, (short) 10,
			LocalDateTime.of(2026, 7, 20, 9, 0, 0), Visibility.PRIVATE);
		Long id = videoRepository.save(video).getId();
		em.createNativeQuery("UPDATE videos SET visibility = :vis, processing_status = :ps, status = :st, "
				+ "created_at = :ts WHERE id = :id")
			.setParameter("vis", visibility.name())
			.setParameter("ps", processingStatus.name())
			.setParameter("st", status.name())
			.setParameter("ts", createdAtUtc)
			.setParameter("id", id)
			.executeUpdate();
	}

	/** 게이트를 모두 통과하는(PUBLIC·READY·ACTIVE) 영상. */
	private void publicReady(Long ownerId, String gridId, LocalDateTime createdAtUtc) {
		saveVideo(ownerId, gridId, Visibility.PUBLIC, ProcessingStatus.READY, VideoStatus.ACTIVE, createdAtUtc);
	}

	private List<HourlyUploadProjection> counts(String gridId) {
		em.flush();
		em.clear();
		return videoRepository.countHourlyUploadsByGrid(gridId, STORED_ZONE);
	}

	/** UTC 벽시계 시각 — KST 는 여기서 +9 시간이다. */
	private static LocalDateTime utc(int hour) {
		return LocalDateTime.of(2026, 7, 20, hour, 30, 0);
	}

	@Test
	// 검증: FR-MAP-09
	@DisplayName("공개 영상의 업로드 시각이 KST 시간대로 접혀 집계된다")
	void 공개_영상의_업로드_시각이_KST_시간대로_접혀_집계된다() {
		publicReady(userId, targetGridId, utc(1));    // KST 10시
		publicReady(userId, targetGridId, utc(1));    // KST 10시
		publicReady(userId, targetGridId, utc(4));    // KST 13시

		assertThat(counts(targetGridId))
			.extracting(HourlyUploadProjection::getHour, HourlyUploadProjection::getCount)
			.containsExactly(tuple(10, 2L), tuple(13, 1L));
	}

	@Test
	@DisplayName("UTC 저장 시각이 KST 로 변환되어 경계 시간대에 바르게 접힌다 (9시간 스큐 검출)")
	void UTC_저장_시각이_KST로_변환되어_경계_시간대에_바르게_접힌다() {
		publicReady(userId, targetGridId, LocalDateTime.of(2026, 7, 20, 15, 30, 0));   // KST 다음날 0시
		publicReady(userId, targetGridId, LocalDateTime.of(2026, 7, 20, 14, 59, 59));  // KST 23시

		assertThat(counts(targetGridId))
			.extracting(HourlyUploadProjection::getHour, HourlyUploadProjection::getCount)
			.containsExactly(tuple(0, 1L), tuple(23, 1L));
	}

	@Test
	@DisplayName("비공개 영상은 집계에서 빠진다 (PRIVATE·FRIENDS)")
	void 비공개_영상은_집계에서_빠진다() {
		saveVideo(userId, targetGridId, Visibility.PRIVATE, ProcessingStatus.READY, VideoStatus.ACTIVE, utc(1));
		saveVideo(userId, targetGridId, Visibility.FRIENDS, ProcessingStatus.READY, VideoStatus.ACTIVE, utc(1));

		assertThat(counts(targetGridId)).isEmpty();
	}

	@Test
	@DisplayName("삭제되거나 블라인드된 영상은 집계에서 빠진다")
	void 삭제되거나_블라인드된_영상은_집계에서_빠진다() {
		saveVideo(userId, targetGridId, Visibility.PUBLIC, ProcessingStatus.READY, VideoStatus.DELETED, utc(1));
		saveVideo(userId, targetGridId, Visibility.PUBLIC, ProcessingStatus.READY, VideoStatus.BLINDED, utc(1));

		assertThat(counts(targetGridId)).isEmpty();
	}

	@Test
	@DisplayName("인코딩 미완 영상은 집계에서 빠진다")
	void 인코딩_미완_영상은_집계에서_빠진다() {
		for (ProcessingStatus processingStatus : ProcessingStatus.values()) {
			if (processingStatus != ProcessingStatus.READY) {
				saveVideo(userId, targetGridId, Visibility.PUBLIC, processingStatus, VideoStatus.ACTIVE, utc(1));
			}
		}

		assertThat(counts(targetGridId)).isEmpty();
	}

	@Test
	@DisplayName("다른 사용자의 공개 영상도 함께 센다 (전역 기준)")
	void 다른_사용자의_공개_영상도_함께_센다() {
		Long otherUserId = newUser("타인");
		publicReady(otherUserId, targetGridId, utc(1));
		publicReady(userId, targetGridId, utc(1));

		assertThat(counts(targetGridId))
			.extracting(HourlyUploadProjection::getHour, HourlyUploadProjection::getCount)
			.containsExactly(tuple(10, 2L));
	}

	@Test
	@DisplayName("다른 격자의 영상은 세지 않는다")
	void 다른_격자의_영상은_세지_않는다() {
		publicReady(userId, otherGridId, utc(1));

		assertThat(counts(targetGridId)).isEmpty();
	}

	@Test
	@DisplayName("업로드가 없는 시간대는 결과에 나오지 않는다 (24구간 채움은 서비스 몫)")
	void 업로드가_없는_시간대는_결과에_나오지_않는다() {
		publicReady(userId, targetGridId, utc(1));

		assertThat(counts(targetGridId)).hasSize(1);
	}

	@Test
	@DisplayName("전체 누적 집계라 오래된 영상과 최근 영상을 모두 센다")
	void 전체_누적_집계라_오래된_영상과_최근_영상을_모두_센다() {
		publicReady(userId, targetGridId, LocalDateTime.of(2020, 1, 1, 1, 30));   // KST 10시
		publicReady(userId, targetGridId, LocalDateTime.of(2026, 7, 20, 4, 30));  // KST 13시

		assertThat(counts(targetGridId))
			.extracting(HourlyUploadProjection::getHour, HourlyUploadProjection::getCount)
			.containsExactly(tuple(10, 1L), tuple(13, 1L));
	}

	@Test
	@DisplayName("시간대 집계와 전역 영상 목록이 같은 후보 집합을 센다")
	void 시간대_집계와_전역_영상_목록이_같은_후보_집합을_센다() {
		publicReady(userId, targetGridId, utc(1));
		publicReady(userId, targetGridId, utc(4));
		saveVideo(userId, targetGridId, Visibility.PRIVATE, ProcessingStatus.READY, VideoStatus.ACTIVE, utc(5));
		saveVideo(userId, targetGridId, Visibility.PUBLIC, ProcessingStatus.FAILED, VideoStatus.ACTIVE, utc(6));
		saveVideo(userId, targetGridId, Visibility.PUBLIC, ProcessingStatus.READY, VideoStatus.DELETED, utc(7));
		publicReady(userId, otherGridId, utc(8));

		long hourlyTotal = counts(targetGridId).stream().mapToLong(HourlyUploadProjection::getCount).sum();

		assertThat(hourlyTotal).isEqualTo(videoRepository.findGlobalVideos(targetGridId, 100).size());
		assertThat(hourlyTotal).isEqualTo(2L);
	}
}

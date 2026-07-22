package com.msg.fillmap.video.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * MSG-145: AI 하이라이트·블러 결과 저장 스키마(V3)를 실 PostGIS·Flyway 로 검증한다.
 * blurred_s3_key·highlights(JSONB)·ai_job_id 매핑과 CHECK 제약(최대 3구간)이 대상이다.
 * 상태 전이(BLURRING→READY)는 이 티켓 범위 밖이라 검증하지 않는다(MSG-150).
 */
@SpringBootTest
@Transactional
@DisplayName("Video AI 블러 결과 저장 (실 PostGIS · Flyway V3)")
class VideoBlurResultTest {

	private static final double 성수_LAT = 37.5445;
	private static final double 성수_LON = 127.0560;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private Long userId;
	private String gridId;

	@BeforeEach
	void setUp() {
		userId = userRepository.save(User.createLocalUser("blur-result@example.com", "hash", "테스터")).getId();
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

	private Video newVideo() {
		String key = "videos/original/" + UUID.randomUUID() + ".mp4";   // uq_videos_original_s3_key
		return Video.create(userId, gridId, key, GeoSupport.toPoint(성수_LAT, 성수_LON), (short) 10,
			LocalDateTime.now());
	}

	private Video persistAndReload(Video video) {
		Long id = videoRepository.save(video).getId();
		em.flush();
		em.clear();
		return videoRepository.findById(id).orElseThrow();
	}

	@Test
	@DisplayName("삭제된 영상은 reconcile 조회(ACTIVE·BLURRING)에서 제외된다")
	void 삭제된_영상은_reconcile_조회에서_제외된다() {
		Long activeId = videoRepository.save(newVideo()).getId();
		Long deletedId = videoRepository.save(newVideo()).getId();
		em.createNativeQuery("UPDATE videos SET processing_status = 'BLURRING' WHERE id IN (:a, :d)")
			.setParameter("a", activeId).setParameter("d", deletedId).executeUpdate();
		em.createNativeQuery("UPDATE videos SET status = 'DELETED' WHERE id = :d")
			.setParameter("d", deletedId).executeUpdate();
		em.flush();
		em.clear();

		List<Video> targets = videoRepository.findByStatusAndProcessingStatus(
			VideoStatus.ACTIVE, ProcessingStatus.BLURRING);

		assertThat(targets).extracting(Video::getId).contains(activeId).doesNotContain(deletedId);
	}

	@Nested
	@DisplayName("Flyway 마이그레이션 적용")
	class FlywayMigration {

		@Test
		@DisplayName("V3 마이그레이션 적용 후 videos 에 세 컬럼이 존재한다")
		@SuppressWarnings("unchecked")
		void V3_마이그레이션_적용_후_videos에_세_컬럼이_존재한다() {
			List<String> columns = em.createNativeQuery(
					"SELECT column_name FROM information_schema.columns "
						+ "WHERE table_name = 'videos' "
						+ "AND column_name IN ('blurred_s3_key', 'highlights', 'ai_job_id')")
				.getResultList();

			assertThat(columns).containsExactlyInAnyOrder("blurred_s3_key", "highlights", "ai_job_id");
		}

		@Test
		@DisplayName("BLURRING 은 processing_status CHECK 를 통과한다")
		void BLURRING은_processing_status_CHECK를_통과한다() {
			Long id = videoRepository.save(newVideo()).getId();

			em.createNativeQuery("UPDATE videos SET processing_status = 'BLURRING' WHERE id = :id")
				.setParameter("id", id)
				.executeUpdate();
			em.flush();
			em.clear();

			Video found = videoRepository.findById(id).orElseThrow();
			assertThat(found.getProcessingStatus()).isEqualTo(ProcessingStatus.BLURRING);
		}
	}

	@Nested
	@DisplayName("엔티티 라운드트립")
	class Roundtrip {

		@Test
		@DisplayName("블러본키와 하이라이트를 저장하고 다시 읽으면 값이 보존된다")
		void 블러본키와_하이라이트를_저장하고_다시_읽으면_값이_보존된다() {
			Video video = newVideo();
			video.applyBlurResult("videos/blurred/1/7.mp4", List.of(List.of(2.0, 6.0)));

			Video found = persistAndReload(video);

			assertThat(found.getBlurredS3Key()).isEqualTo("videos/blurred/1/7.mp4");
			assertThat(found.getHighlights()).containsExactly(List.of(2.0, 6.0));
		}

		@Test
		@DisplayName("하이라이트 JSONB 는 구간 배열로 라운드트립된다")
		void 하이라이트_JSONB는_구간_배열로_라운드트립된다() {
			Video video = newVideo();
			video.applyBlurResult("videos/blurred/1/8.mp4", List.of(List.of(0.0, 3.0), List.of(10.0, 14.0)));

			Video found = persistAndReload(video);

			assertThat(found.getHighlights())
				.hasSize(2)
				.containsExactly(List.of(0.0, 3.0), List.of(10.0, 14.0));
		}

		@Test
		@DisplayName("하이라이트 초는 소수점까지 라운드트립된다")
		void 하이라이트_초는_소수점까지_라운드트립된다() {
			Video video = newVideo();   // AI 는 round(x,2) 소수점 초를 반환한다 (bench.py:107)
			video.applyBlurResult("videos/blurred/1/12.mp4", List.of(List.of(0.0, 3.33), List.of(3.33, 6.67)));

			Video found = persistAndReload(video);

			assertThat(found.getHighlights())
				.containsExactly(List.of(0.0, 3.33), List.of(3.33, 6.67));
		}

		@Test
		@DisplayName("하이라이트가 없으면 null 로 저장된다")
		void 하이라이트가_없으면_null로_저장된다() {
			Video video = newVideo();
			video.applyBlurResult("videos/blurred/1/9.mp4", null);

			Video found = persistAndReload(video);

			assertThat(found.getBlurredS3Key()).isEqualTo("videos/blurred/1/9.mp4");
			assertThat(found.getHighlights()).isNull();
		}
	}

	@Nested
	@DisplayName("CHECK 제약 위반")
	class CheckConstraint {

		@Test
		@DisplayName("하이라이트가 4구간이면 제약 위반으로 저장에 실패한다")
		void 하이라이트가_4구간이면_제약_위반으로_저장에_실패한다() {
			Video video = newVideo();
			video.applyBlurResult("videos/blurred/1/10.mp4",
				List.of(List.of(0.0, 1.0), List.of(2.0, 3.0), List.of(4.0, 5.0), List.of(6.0, 7.0)));

			assertThatThrownBy(() -> videoRepository.saveAndFlush(video))
				.isInstanceOf(DataIntegrityViolationException.class);
		}

		@Test
		@DisplayName("하이라이트가 빈배열이면 저장된다")
		void 하이라이트가_빈배열이면_저장된다() {
			Video video = newVideo();
			video.applyBlurResult("videos/blurred/1/11.mp4", List.<List<Double>>of());

			Video found = persistAndReload(video);

			assertThat(found.getHighlights()).isEmpty();
		}
	}
}

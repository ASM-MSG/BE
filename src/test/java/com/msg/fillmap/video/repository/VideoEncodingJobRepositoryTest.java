package com.msg.fillmap.video.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoEncodingJobStatus;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.service.EncodingJobClaim;
import com.msg.fillmap.video.support.GeoSupport;

@SpringBootTest
@DisplayName("VideoEncodingJobRepository 작업 선점 (실 PostgreSQL)")
class VideoEncodingJobRepositoryTest {

	private static final long GY = 17810L;
	private static final long GX = 7794L;
	private static final Duration LEASE = Duration.ofMinutes(35);

	@Autowired
	private VideoEncodingJobRepository repository;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long userId;
	private String gridId;
	private long videoId;
	private String originalKey;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> {
			userId = userRepository.save(User.createLocalUser(
				"m494-job-" + System.nanoTime() + "@example.com", "hash", "작업선점")).getId();
			gridId = GridFixtures.seedGrid(em, GY, GX);
			videoId = saveVideo("first");
			originalKey = originalKey(videoId);
		});
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status -> {
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
			jdbcTemplate.update("DELETE FROM grids WHERE grid_id = ?", gridId);
		});
	}

	@Test
	@DisplayName("같은 영상 시도를 두 번 등록해도 작업은 하나다")
	void 같은_영상_시도를_두번_등록해도_작업은_하나다() {
		repository.enqueue(videoId, originalKey);
		repository.enqueue(videoId, originalKey);

		assertThat(jobCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("두 트랜잭션이 동시에 선점하면 서로 다른 작업을 가져간다")
	void 두_트랜잭션이_동시에_선점하면_서로_다른_작업을_가져간다() throws Exception {
		long secondVideoId = tx.execute(status -> saveVideo("second"));
		repository.enqueue(videoId, originalKey);
		repository.enqueue(secondVideoId, originalKey(secondVideoId));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<EncodingJobClaim> first = pool.submit(() ->
				repository.claimNext("be", UUID.randomUUID(), LEASE).orElseThrow());
			Future<EncodingJobClaim> second = pool.submit(() ->
				repository.claimNext("ai", UUID.randomUUID(), LEASE).orElseThrow());

			assertThat(List.of(first.get(10, TimeUnit.SECONDS).videoId(),
				second.get(10, TimeUnit.SECONDS).videoId())).containsExactlyInAnyOrder(videoId, secondVideoId);
		} finally {
			pool.shutdownNow();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	@DisplayName("처리 중 임대가 남았으면 다른 노드가 가져가지 못한다")
	void 처리중_임대가_남았으면_다른_노드가_가져가지_못한다() {
		repository.enqueue(videoId, originalKey);
		repository.claimNext("be", UUID.randomUUID(), LEASE).orElseThrow();

		assertThat(repository.claimNext("ai", UUID.randomUUID(), LEASE)).isEmpty();
	}

	@Test
	@DisplayName("임대가 끝난 작업은 새 토큰으로 재선점된다")
	void 임대가_끝난_작업은_새_token으로_재선점된다() {
		repository.enqueue(videoId, originalKey);
		UUID oldToken = UUID.randomUUID();
		EncodingJobClaim oldClaim = repository.claimNext("be", oldToken, LEASE).orElseThrow();
		expireLease(oldClaim.jobId());
		UUID newToken = UUID.randomUUID();

		EncodingJobClaim reclaimed = repository.claimNext("ai", newToken, LEASE).orElseThrow();

		assertThat(reclaimed.jobId()).isEqualTo(oldClaim.jobId());
		assertThat(reclaimed.claimToken()).isEqualTo(newToken);
		assertThat(reclaimed.attemptCount()).isEqualTo((short)2);
	}

	@Test
	@DisplayName("KST 세션에서도 살아있는 임대는 만료로 보지 않는다")
	void KST_세션에서도_살아있는_임대는_만료로_보지_않는다() {
		repository.enqueue(videoId, originalKey);
		repository.claimNext("be", UUID.randomUUID(), LEASE).orElseThrow();

		EncodingJobClaim result = tx.execute(status -> {
			jdbcTemplate.execute("SET LOCAL TIME ZONE 'Asia/Seoul'");
			return repository.claimNext("ai", UUID.randomUUID(), LEASE).orElse(null);
		});

		assertThat(result).isNull();
	}

	@Test
	@DisplayName("KST 세션에서도 작업 기본 시각은 UTC 벽시계로 저장된다")
	void KST_세션에서도_작업_기본시각은_UTC_벽시계로_저장된다() {
		LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
		tx.executeWithoutResult(status -> {
			jdbcTemplate.execute("SET LOCAL TIME ZONE 'Asia/Seoul'");
			repository.enqueue(videoId, originalKey);
		});
		LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

		LocalDateTime enqueuedAt = jdbcTemplate.queryForObject(
			"SELECT enqueued_at FROM video_encoding_jobs WHERE video_id = ?", LocalDateTime.class, videoId);
		assertThat(enqueuedAt).isBetween(before, after);
	}

	@Test
	@DisplayName("V44 backfill은 원본 키가 null인 영상을 제외한다")
	void V44_backfill은_원본키가_null인_영상을_제외한다() throws Exception {
		String migration = new ClassPathResource("db/migration/V44__video_encoding_jobs.sql")
			.getContentAsString(StandardCharsets.UTF_8);

		assertThat(migration).contains("AND original_s3_key IS NOT NULL");
	}

	@Test
	@DisplayName("옛 토큰은 완료와 재시도 반환을 갱신하지 못한다")
	void 옛_token은_완료와_재시도_반환을_갱신하지_못한다() {
		repository.enqueue(videoId, originalKey);
		EncodingJobClaim oldClaim = repository.claimNext("be", UUID.randomUUID(), LEASE).orElseThrow();
		expireLease(oldClaim.jobId());
		repository.claimNext("ai", UUID.randomUUID(), LEASE).orElseThrow();

		assertThat(repository.complete(oldClaim)).isZero();
		assertThat(repository.retry(oldClaim, Duration.ofSeconds(5), "old failure")).isZero();
		assertThat(statusOf(oldClaim.jobId())).isEqualTo(VideoEncodingJobStatus.PROCESSING.name());
	}

	@Test
	@DisplayName("세 번째 시도의 임대가 끝나면 DEAD 후보가 된다")
	void 세번째_시도의_임대가_끝나면_DEAD_후보가_된다() {
		repository.enqueue(videoId, originalKey);
		long jobId = jobId();
		jdbcTemplate.update("""
			UPDATE video_encoding_jobs
			SET status = 'PROCESSING', attempt_count = 3, claim_token = ?,
				claimed_by = 'be', lease_until = (statement_timestamp() AT TIME ZONE 'utc') - interval '1 second'
			WHERE id = ?
			""", UUID.randomUUID(), jobId);

		assertThat(repository.markExpiredThirdAttemptsDead()).isEqualTo(1);
		assertThat(statusOf(jobId)).isEqualTo(VideoEncodingJobStatus.DEAD.name());
	}

	@Test
	@DisplayName("살아있는 DEAD finalizer의 토큰은 덮어쓰지 않는다")
	void 살아있는_DEAD_finalizer의_token은_덮어쓰지_않는다() {
		repository.enqueue(videoId, originalKey);
		long jobId = jobId();
		jdbcTemplate.update("UPDATE video_encoding_jobs SET status = 'DEAD' WHERE id = ?", jobId);
		EncodingJobClaim first = repository.claimDead("be", UUID.randomUUID(), LEASE).orElseThrow();

		assertThat(repository.claimDead("ai", UUID.randomUUID(), LEASE)).isEmpty();
		expireLease(first.jobId());
		assertThat(repository.claimDead("ai", UUID.randomUUID(), LEASE)).isPresent();
	}

	@Test
	@DisplayName("회원 탈퇴로 video 행이 삭제되면 작업도 cascade 삭제된다")
	void 회원탈퇴로_video행이_삭제되면_작업도_cascade_삭제된다() {
		repository.enqueue(videoId, originalKey);

		jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);

		assertThat(jobCount()).isZero();
	}

	private long saveVideo(String suffix) {
		GridPoint center = GridFixtures.pointAt(GY + 0.5, GX + 0.5);
		String key = "videos/original/%d/m494-%s-%s.mp4".formatted(userId, suffix, UUID.randomUUID());
		return videoRepository.save(Video.create(
			userId, gridId, key, GeoSupport.toPoint(center.lat(), center.lon()),
			(short)10, LocalDateTime.now(ZoneOffset.UTC), Visibility.PRIVATE)).getId();
	}

	private String originalKey(long id) {
		return jdbcTemplate.queryForObject(
			"SELECT original_s3_key FROM videos WHERE id = ?", String.class, id);
	}

	private long jobId() {
		return jdbcTemplate.queryForObject(
			"SELECT id FROM video_encoding_jobs WHERE video_id = ?", Long.class, videoId);
	}

	private int jobCount() {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM video_encoding_jobs WHERE video_id = ?", Integer.class, videoId);
	}

	private String statusOf(long id) {
		return jdbcTemplate.queryForObject(
			"SELECT status FROM video_encoding_jobs WHERE id = ?", String.class, id);
	}

	private void expireLease(long id) {
		jdbcTemplate.update("""
			UPDATE video_encoding_jobs
			SET lease_until = (statement_timestamp() AT TIME ZONE 'utc') - interval '1 second'
			WHERE id = ?
			""", id);
	}
}

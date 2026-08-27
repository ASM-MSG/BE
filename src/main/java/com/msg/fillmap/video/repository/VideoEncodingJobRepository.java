package com.msg.fillmap.video.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.video.entity.VideoEncodingJobStatus;
import com.msg.fillmap.video.service.EncodingJobClaim;

@Repository
public class VideoEncodingJobRepository {

	private static final int MAX_ERROR_LENGTH = 1000;

	private final JdbcClient jdbcClient;

	public VideoEncodingJobRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Transactional
	public void enqueue(Long videoId, String originalS3Key) {
		jdbcClient.sql("""
			INSERT INTO video_encoding_jobs (video_id, original_s3_key)
			VALUES (:videoId, :originalS3Key)
			ON CONFLICT (video_id, original_s3_key) DO NOTHING
			""")
			.param("videoId", videoId)
			.param("originalS3Key", originalS3Key)
			.update();
	}

	@Transactional
	public Optional<EncodingJobClaim> claimNext(String nodeId, UUID claimToken, Duration lease) {
		return jdbcClient.sql("""
			WITH clock AS (
				SELECT statement_timestamp() AT TIME ZONE 'utc' AS now_utc
			), candidate AS (
				SELECT j.id
				FROM video_encoding_jobs j
				CROSS JOIN clock c
				WHERE (j.status = 'PENDING' AND j.available_at <= c.now_utc AND j.attempt_count < 3)
					OR (j.status = 'PROCESSING' AND j.lease_until <= c.now_utc AND j.attempt_count < 3)
				ORDER BY j.available_at, j.id
				FOR UPDATE OF j SKIP LOCKED
				LIMIT 1
			)
			UPDATE video_encoding_jobs j
			SET status = 'PROCESSING',
				attempt_count = j.attempt_count + 1,
				claim_token = :claimToken,
				claimed_by = :nodeId,
				lease_until = c.now_utc + make_interval(secs => :leaseSeconds)
			FROM candidate, clock c
			WHERE j.id = candidate.id
			RETURNING j.id, j.video_id, j.original_s3_key, j.claim_token,
				j.attempt_count, j.enqueued_at
			""")
			.param("claimToken", claimToken)
			.param("nodeId", nodeId)
			.param("leaseSeconds", seconds(lease))
			.query(VideoEncodingJobRepository::mapClaim)
			.optional();
	}

	@Transactional
	public int retry(EncodingJobClaim claim, Duration delay, String error) {
		return jdbcClient.sql("""
			UPDATE video_encoding_jobs
			SET status = 'PENDING',
				claim_token = NULL,
				claimed_by = NULL,
				lease_until = NULL,
				available_at = (statement_timestamp() AT TIME ZONE 'utc')
					+ make_interval(secs => :delaySeconds),
				last_error = :error
			WHERE id = :jobId
				AND status = 'PROCESSING'
				AND claim_token = :claimToken
				AND attempt_count < 3
				AND lease_until > (statement_timestamp() AT TIME ZONE 'utc')
			""")
			.param("delaySeconds", seconds(delay))
			.param("error", truncate(error))
			.param("jobId", claim.jobId())
			.param("claimToken", claim.claimToken())
			.update();
	}

	@Transactional
	public int complete(EncodingJobClaim claim) {
		return jdbcClient.sql("""
			UPDATE video_encoding_jobs
			SET status = 'COMPLETED',
				claim_token = NULL,
				claimed_by = NULL,
				lease_until = NULL,
				completed_at = statement_timestamp() AT TIME ZONE 'utc'
			WHERE id = :jobId
				AND status = 'PROCESSING'
				AND claim_token = :claimToken
				AND lease_until > (statement_timestamp() AT TIME ZONE 'utc')
			""")
			.param("jobId", claim.jobId())
			.param("claimToken", claim.claimToken())
			.update();
	}

	@Transactional
	public int release(EncodingJobClaim claim) {
		return jdbcClient.sql("""
			UPDATE video_encoding_jobs
			SET status = CASE WHEN status = 'PROCESSING' THEN 'PENDING' ELSE 'DEAD' END,
				attempt_count = CASE WHEN status = 'PROCESSING' THEN attempt_count - 1 ELSE attempt_count END,
				claim_token = NULL,
				claimed_by = NULL,
				lease_until = NULL,
				available_at = statement_timestamp() AT TIME ZONE 'utc'
			WHERE id = :jobId
				AND status IN ('PROCESSING', 'DEAD')
				AND claim_token = :claimToken
				AND attempt_count > 0
				AND lease_until > (statement_timestamp() AT TIME ZONE 'utc')
			""")
			.param("jobId", claim.jobId())
			.param("claimToken", claim.claimToken())
			.update();
	}

	@Transactional
	public int markExpiredThirdAttemptsDead() {
		return jdbcClient.sql("""
			UPDATE video_encoding_jobs
			SET status = 'DEAD', claim_token = NULL, claimed_by = NULL, lease_until = NULL
			WHERE status = 'PROCESSING'
				AND attempt_count = 3
				AND lease_until <= (statement_timestamp() AT TIME ZONE 'utc')
			""")
			.update();
	}

	@Transactional
	public Optional<EncodingJobClaim> claimDead(String nodeId, UUID claimToken, Duration lease) {
		return jdbcClient.sql("""
			WITH clock AS (
				SELECT statement_timestamp() AT TIME ZONE 'utc' AS now_utc
			), candidate AS (
				SELECT j.id
				FROM video_encoding_jobs j
				CROSS JOIN clock c
				WHERE j.status = 'DEAD'
					AND j.completed_at IS NULL
					AND (j.claim_token IS NULL OR j.lease_until <= c.now_utc)
				ORDER BY j.available_at, j.id
				FOR UPDATE OF j SKIP LOCKED
				LIMIT 1
			)
			UPDATE video_encoding_jobs j
			SET claim_token = :claimToken,
				claimed_by = :nodeId,
				lease_until = c.now_utc + make_interval(secs => :leaseSeconds)
			FROM candidate, clock c
			WHERE j.id = candidate.id
			RETURNING j.id, j.video_id, j.original_s3_key, j.claim_token,
				j.attempt_count, j.enqueued_at
			""")
			.param("claimToken", claimToken)
			.param("nodeId", nodeId)
			.param("leaseSeconds", seconds(lease))
			.query(VideoEncodingJobRepository::mapClaim)
			.optional();
	}

	@Transactional
	public int completeDead(EncodingJobClaim claim) {
		return jdbcClient.sql("""
			UPDATE video_encoding_jobs
			SET completed_at = statement_timestamp() AT TIME ZONE 'utc',
				claim_token = NULL,
				claimed_by = NULL,
				lease_until = NULL
			WHERE id = :jobId
				AND status = 'DEAD'
				AND completed_at IS NULL
				AND claim_token = :claimToken
				AND lease_until > (statement_timestamp() AT TIME ZONE 'utc')
			""")
			.param("jobId", claim.jobId())
			.param("claimToken", claim.claimToken())
			.update();
	}

	public long count(VideoEncodingJobStatus status) {
		return jdbcClient.sql("SELECT count(*) FROM video_encoding_jobs WHERE status = :status")
			.param("status", status.name())
			.query(Long.class)
			.single();
	}

	private static EncodingJobClaim mapClaim(ResultSet resultSet, int rowNumber) throws SQLException {
		return new EncodingJobClaim(
			resultSet.getLong("id"),
			resultSet.getLong("video_id"),
			resultSet.getString("original_s3_key"),
			resultSet.getObject("claim_token", UUID.class),
			resultSet.getShort("attempt_count"),
			resultSet.getTimestamp("enqueued_at").toLocalDateTime()
		);
	}

	private static double seconds(Duration duration) {
		return duration.toMillis() / 1000.0;
	}

	private static String truncate(String error) {
		if (error == null || error.length() <= MAX_ERROR_LENGTH) {
			return error;
		}
		return error.substring(0, MAX_ERROR_LENGTH);
	}
}

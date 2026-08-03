package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;

/**
 * 업로드 훅의 핫스코어 afterCommit 배선 통합 검증 (실 Redis, MSG-183). 테스트 트랜잭션은 커밋되지
 * 않으므로 saveVideo 리턴 직후 점수 무변이면 훅이 커밋 전에 돌지 않는다는 증명이다 — 롤백 시 유령
 * 증분 방지(D6)의 테스트 트랜잭션 환경 표현. VideoStreakIntegrationTest 선례: 공유 로컬 DB, S3 mock.
 */
@SpringBootTest
@Transactional
@DisplayName("업로드 훅 핫스코어 배선 (실 Redis)")
class VideoHotScoreIntegrationTest {

	// 다른 테스트(성수·잠실·뚝섬·망원)와 겹치지 않는 좌표 (문래).
	private static final double 문래_LAT = 37.5178;
	private static final double 문래_LON = 126.8946;

	@Autowired
	private VideoService videoService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private S3Client s3Client;

	private long userId;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		String email = "video-hotscore-" + System.nanoTime() + "@example.com";
		userId = userRepository.save(User.createLocalUser(email, "hash", "핫스코어훅테스터")).getId();
	}

	@Test
	void 업로드_트랜잭션이_커밋되기_전에는_핫스코어가_증가하지_않는다() {
		String gridId = GridEncoder.encode(문래_LAT, 문래_LON);
		// 실행 중 6h 경계를 넘을 수 있어 현재·다음 버킷을 함께 본다 (bucketId = epochSeconds / 21600, D2).
		long bucket = Instant.now().getEpochSecond() / 21600L;
		Double before = score(bucket, gridId);
		Double beforeNext = score(bucket + 1, gridId);

		videoService.saveVideo(userId, uploadRequest());

		assertThat(score(bucket, gridId)).isEqualTo(before);
		assertThat(score(bucket + 1, gridId)).isEqualTo(beforeNext);
	}

	private Double score(long bucketId, String gridId) {
		return redisTemplate.opsForZSet().score("hotzone:" + bucketId, gridId);
	}

	private VideoUploadRequestDto uploadRequest() {
		return new VideoUploadRequestDto(
			"videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4", 문래_LAT, 문래_LON, (short) 10,
			LocalDateTime.now(ZoneOffset.UTC), "PRIVATE");
	}
}

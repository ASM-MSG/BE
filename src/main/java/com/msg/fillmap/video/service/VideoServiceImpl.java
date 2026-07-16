package com.msg.fillmap.video.service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.locationtech.jts.geom.Point;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.video.dto.PresignedUrlRequestDto;
import com.msg.fillmap.video.dto.PresignedUrlResponseDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

// ponytail: presign 을 VideoServiceImpl 에 합침. MSG-71 에서 S3 관심사가 2개째면 PresignedUrlService 로 분리.
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoServiceImpl implements VideoService {

	// 서비스 범위(한국) plausibility 검증용 좌표 경계 — MSG-66 D7.
	private static final double MIN_LAT = 33.0;
	private static final double MAX_LAT = 39.0;
	private static final double MIN_LON = 124.0;
	private static final double MAX_LON = 132.0;

	// 허용 확장자 → 정규 Content-Type — MSG-64 D2. 쌍으로 검증해 엇갈린 조합을 막는다.
	private static final Map<String, String> ALLOWED_TYPES = Map.of(
		"mp4", "video/mp4",
		"mov", "video/quicktime");

	private static final Duration PRESIGN_TTL = Duration.ofMinutes(10);

	private final VideoRepository videoRepository;
	private final VideoEncodingService videoEncodingService;
	private final VideoStatusWriter videoStatusWriter;
	private final S3Presigner s3Presigner;
	private final S3Client s3Client;
	private final AwsProperties awsProperties;

	@Override
	@Transactional
	public VideoUploadResponseDto saveVideo(long userId, VideoUploadRequestDto request) {
		double lat = request.lat();
		double lon = request.lon();
		validateCoordinate(lat, lon);
		validateUploadedS3Key(userId, request.s3Key());

		String gridId = GridEncoder.encode(lat, lon);
		registerGridIfAbsent(gridId);

		boolean alreadyOccupied = videoRepository.existsUserGrid(userId, gridId);

		Point geom = GeoSupport.toPoint(lat, lon);
		Video video = videoRepository.save(
			Video.create(userId, gridId, request.s3Key(), geom, request.durationSec(), request.recordedAt()));

		videoRepository.upsertUserGrid(userId, gridId, video.getId());
		triggerEncodingAfterCommit(video.getId());

		return new VideoUploadResponseDto(
			video.getId(), gridId, video.getProcessingStatus().name(), !alreadyOccupied);
	}

	@Override
	@Transactional
	public void deleteVideo(long userId, long videoId) {
		Video video = videoRepository.findById(videoId)
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		if (video.getUserId() != userId) {
			throw new ApiException(VideoErrorCode.VIDEO_FORBIDDEN);
		}
		if (video.isDeleted()) {
			return;   // 중복 삭제는 멱등하게 성공 (MSG-72 D7)
		}

		// 아래 native 쿼리들은 이 변경을 본다 — Hibernate 가 native 쿼리 전에 영속성 컨텍스트를
		// 자동 flush 하기 때문이다(FlushMode.AUTO). 그러지 않으면 cover 재선정이 방금 지운 영상을
		// 다시 고른다. VideoDeleteIntegrationTest 의 cover 재선정 테스트가 이 순서를 지킨다.
		video.markDeleted();

		String gridId = video.getGridId();
		videoRepository.decrementVideoCount(userId, gridId);
		if (videoRepository.deleteUserGridIfEmpty(userId, gridId) == 0) {
			// 아직 그 격자에 내 영상이 남아 있다 — 점령은 유지하고 cover 만 정리한다.
			videoRepository.reselectCover(userId, gridId, videoId);
		}
	}

	/**
	 * 인코딩은 커밋 이후에 띄운다. @Async 는 별도 스레드라 여기서 바로 호출하면 아직 커밋되지 않은
	 * videos row 를 조회해 "영상 없음"으로 실패할 수 있다 (MSG-65 트리거 타이밍).
	 */
	private void triggerEncodingAfterCommit(Long videoId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			submitEncoding(videoId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				submitEncoding(videoId);
			}
		});
	}

	/**
	 * 큐가 가득 차면 executor 가 TaskRejectedException 을 던지는데, 이는 @Async 메서드 본문이 아니라
	 * submit 을 호출한 이 스레드에서 터진다. 그냥 두면 afterCommit 밖으로 전파돼 이미 커밋된 업로드가
	 * 500 으로 응답되고(클라이언트는 재시도 → 중복 업로드), 인코딩 쪽 catch 는 실행조차 되지 않아
	 * 영상이 UPLOADED 로 남는다. 그래서 여기서 삼키고 FAILED 로 기록한다.
	 */
	private void submitEncoding(Long videoId) {
		try {
			videoEncodingService.encode(videoId);
		} catch (TaskRejectedException e) {
			log.error("인코딩 큐 포화로 작업이 거부됨: videoId={}", videoId, e);
			videoStatusWriter.markFailed(videoId);
		}
	}

	@Override
	public PresignedUrlResponseDto issuePresignedUrl(long userId, PresignedUrlRequestDto request) {
		String extension = request.extension().toLowerCase();
		String allowedType = ALLOWED_TYPES.get(extension);
		if (allowedType == null || !allowedType.equals(request.contentType())) {
			throw new ApiException(VideoErrorCode.UNSUPPORTED_EXTENSION);
		}
		if (request.contentLength() > awsProperties.s3().maxUploadBytes()) {
			throw new ApiException(VideoErrorCode.FILE_TOO_LARGE);
		}

		String s3Key = "videos/original/%d/%s.%s".formatted(userId, UUID.randomUUID(), extension);

		// contentLength/contentType 을 서명에 포함시켜 클라이언트가 선언과 다른 크기·타입으로 올리면 S3 가 403 을 낸다.
		// (PUT presign 에는 POST policy 의 content-length-range 같은 범위 조건이 없다 — MSG-64 D3)
		PutObjectRequest objectRequest = PutObjectRequest.builder()
			.bucket(awsProperties.s3().bucket())
			.key(s3Key)
			.contentType(request.contentType())
			.contentLength(request.contentLength())
			.build();

		String uploadUrl = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
			.signatureDuration(PRESIGN_TTL)
			.putObjectRequest(objectRequest)
			.build()).url().toString();

		return new PresignedUrlResponseDto(uploadUrl, s3Key, PRESIGN_TTL.toSeconds());
	}

	private void validateCoordinate(double lat, double lon) {
		if (lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
			throw new ApiException(VideoErrorCode.INVALID_COORDINATE);
		}
	}

	/**
	 * 확정 요청의 s3Key 가 "내가 실제로 올린, 아직 안 쓴 파일"인지 확인한다 (MSG-132).
	 *
	 * 이 검증이 없으면 파일을 올리지 않고 좌표만 찍어서 격자를 점령할 수 있다 — upsertUserGrid 가
	 * 인코딩보다 먼저 돌고, 인코딩이 FAILED 가 돼도 점령은 남기 때문이다. FillMap 은 직접 가서 찍어야
	 * 격자를 채우는 게임이라 이건 게임 자체를 무너뜨린다.
	 *
	 * prefix 검사만으론 부족하다 — 공격자는 자기 userId 를 알기에 videos/original/{내id}/아무거나.mp4 를
	 * 지어낼 수 있다. S3 에 실제로 있는지(headObject) 봐야 막힌다.
	 */
	private void validateUploadedS3Key(long userId, String s3Key) {
		String requiredPrefix = "videos/original/%d/".formatted(userId);
		if (!s3Key.startsWith(requiredPrefix)) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);
		}
		// 영상 1개로 좌표만 바꿔가며 무한 점령하는 걸 막는다. DB 의 UNIQUE 제약이 최종 방어선이고,
		// 여기서 미리 걸러 500 대신 4xx 를 준다.
		if (videoRepository.existsByOriginalS3Key(s3Key)) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);
		}
		requireObjectExists(s3Key);
	}

	private void requireObjectExists(String s3Key) {
		try {
			s3Client.headObject(HeadObjectRequest.builder()
				.bucket(awsProperties.s3().bucket())
				.key(s3Key)
				.build());
		} catch (NoSuchKeyException e) {
			throw new ApiException(VideoErrorCode.UPLOAD_NOT_FOUND, e);
		} catch (S3Exception e) {
			// HeadObject 는 본문 없는 404 를 주므로 SDK 가 NoSuchKeyException 으로 못 좁히는 경우가 있다.
			if (e.statusCode() == 404) {
				throw new ApiException(VideoErrorCode.UPLOAD_NOT_FOUND, e);
			}
			throw e;
		}
	}

	private void registerGridIfAbsent(String gridId) {
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(
			gridId, index.gridY(), index.gridX(), center.lat(), center.lon(), GeoSupport.bboxWkt(gridId));
	}
}

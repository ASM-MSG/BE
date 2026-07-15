package com.msg.fillmap.video.service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;

import software.amazon.awssdk.services.s3.model.PutObjectRequest;
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
	private final S3Presigner s3Presigner;
	private final AwsProperties awsProperties;

	@Override
	@Transactional
	public VideoUploadResponseDto saveVideo(long userId, VideoUploadRequestDto request) {
		double lat = request.lat();
		double lon = request.lon();
		validateCoordinate(lat, lon);

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

	/**
	 * 인코딩은 커밋 이후에 띄운다. @Async 는 별도 스레드라 여기서 바로 호출하면 아직 커밋되지 않은
	 * videos row 를 조회해 "영상 없음"으로 실패할 수 있다 (MSG-65 트리거 타이밍).
	 */
	private void triggerEncodingAfterCommit(Long videoId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			videoEncodingService.encode(videoId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				videoEncodingService.encode(videoId);
			}
		});
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

	private void registerGridIfAbsent(String gridId) {
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(
			gridId, index.gridY(), index.gridX(), center.lat(), center.lon(), GeoSupport.bboxWkt(gridId));
	}
}

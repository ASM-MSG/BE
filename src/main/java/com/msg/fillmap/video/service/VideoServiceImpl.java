package com.msg.fillmap.video.service;

import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

@Service
@RequiredArgsConstructor
public class VideoServiceImpl implements VideoService {

	// 서비스 범위(한국) plausibility 검증용 좌표 경계 — MSG-66 D7.
	private static final double MIN_LAT = 33.0;
	private static final double MAX_LAT = 39.0;
	private static final double MIN_LON = 124.0;
	private static final double MAX_LON = 132.0;

	private final VideoRepository videoRepository;

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

		return new VideoUploadResponseDto(
			video.getId(), gridId, video.getProcessingStatus().name(), !alreadyOccupied);
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

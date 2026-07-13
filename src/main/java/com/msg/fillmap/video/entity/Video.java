package com.msg.fillmap.video.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.locationtech.jts.geom.Point;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방문 이벤트 = 업로드 1건 (videos). 첫 방문이면 점령(user_grids row 생성).
 * grid_id 는 업로드 좌표를 GridEncoder 로 인코딩한 값, region_code 는 별도 판정 티켓 전까지 null.
 */
@Entity
@Table(name = "videos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Video {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "grid_id", nullable = false, length = 20)
	private String gridId;

	@Column(name = "region_code", length = 10)
	private String regionCode;

	@Column(name = "original_s3_key", length = 500)
	private String originalS3Key;

	@Column(name = "encoded_url", columnDefinition = "text")
	private String encodedUrl;

	@Column(name = "thumbnail_url", columnDefinition = "text")
	private String thumbnailUrl;

	@Column(name = "geom", nullable = false, columnDefinition = "geography(Point,4326)")
	private Point geom;

	@Column(name = "duration_sec", nullable = false)
	private Short durationSec;

	@Enumerated(EnumType.STRING)
	@Column(name = "processing_status", nullable = false, length = 20)
	private ProcessingStatus processingStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "visibility", nullable = false, length = 10)
	private Visibility visibility;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private VideoStatus status;

	@Column(name = "view_count", nullable = false)
	private Long viewCount;

	@Column(name = "recorded_at", nullable = false)
	private LocalDateTime recordedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Video(Long userId, String gridId, String originalS3Key, Point geom, Short durationSec,
		LocalDateTime recordedAt) {
		this.userId = userId;
		this.gridId = gridId;
		this.originalS3Key = originalS3Key;
		this.geom = geom;
		this.durationSec = durationSec;
		this.recordedAt = recordedAt;
		this.processingStatus = ProcessingStatus.UPLOADED;
		this.visibility = Visibility.PRIVATE;
		this.status = VideoStatus.ACTIVE;
		this.viewCount = 0L;
	}

	public static Video create(Long userId, String gridId, String originalS3Key, Point geom, Short durationSec,
		LocalDateTime recordedAt) {
		return new Video(userId, gridId, originalS3Key, geom, durationSec, recordedAt);
	}
}

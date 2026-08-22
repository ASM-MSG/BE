package com.msg.fillmap.video.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방문 이벤트 = 업로드 1건 (videos). 첫 방문이면 점령(user_grids row 생성).
 * grid_id 는 업로드 좌표를 GridEncoder 로 인코딩한 값, region_code 는 별도 판정 티켓 전까지 null.
 */
// @DynamicUpdate: 동시 쓰기 축이 여럿(visibility 사용자 쓰기 / 삭제 / 인코딩·AI 폴러)이라 각 트랜잭션이
// 자기 dirty 컬럼만 쓰게 해 낡은 스냅샷이 다른 축의 컬럼을 되돌리는 교차 컬럼 클로버링을 막는다 (MSG-162).
@Entity
@Table(name = "videos")
@DynamicUpdate
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

	/**
	 * 인코딩본 S3 key (예: videos/encoded/1/42.mp4). 컬럼명은 url 이지만 URL 이 아니라 key 를 담는다 —
	 * 버킷이 Block Public Access 라 영구 URL 이 없고 presigned GET 은 TTL 이 있어 저장할 수 없다.
	 * 재생 시 이 key 로 presigned GET 을 발급한다. 컬럼명 정정은 V 파일 재작성을 뜻하므로 하지 않는다(MSG-130).
	 */
	@Column(name = "encoded_url", columnDefinition = "text")
	private String encodedUrl;

	/** 썸네일 S3 key. encodedUrl 과 같은 이유로 URL 이 아니라 key 다. */
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

	/** 블러 처리본 S3 key (1:1). BE 가 AI 서버에서 받아 S3 에 올린 뒤 채운다 (MSG-150). */
	@Column(name = "blurred_s3_key", length = 500)
	private String blurredS3Key;

	/** 하이라이트 구간 [[시작초, 끝초], ...] 최대 3. 초는 소수점(AI 가 round(x,2) 반환). 0구간이면 [] 또는 null (MSG-145). */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "highlights", columnDefinition = "jsonb")
	private List<List<Double>> highlights;

	/** AI 서버 job_id 상관키. 폴링 복구용(MSG-149 폴링, MSG-150 결과반영). */
	@Column(name = "ai_job_id", length = 64)
	private String aiJobId;

	/**
	 * 이번 BLURRING 시도가 시작된 시각 (MSG-149 V4, D4). 타임아웃을 created_at 이 아니라 시도 시작으로 재
	 * — 교체 후 재인코딩은 created_at 이 옛 시각이라 100% 오탐이 나므로, markEncoded 가 시도마다 갱신한다.
	 */
	@Column(name = "blurring_started_at")
	private LocalDateTime blurringStartedAt;

	/** 실패 사유 코드 (MSG-286). 프리체크 탈락 시 콜론 앞 코드(too_dark 등), NULL=시스템 오류 실패. */
	@Column(name = "fail_reason", length = 64)
	private String failReason;

	@Column(name = "recorded_at", nullable = false)
	private LocalDateTime recordedAt;

	/**
	 * 생성 시각. @CreationTimestamp 를 쓰지 않고 생성자에서 UTC 로 직접 넣는다 (MSG-376) —
	 * 그 애너테이션은 JVM 기본 존의 벽시계를 만들어 KST 개발 머신에서 +9h 가 저장되는데, 이 값은
	 * 응답에 실려 전역 코덱이 UTC 로 표기하므로 저장 축이 UTC 여야 한다.
	 */
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Video(Long userId, String gridId, String originalS3Key, Point geom, Short durationSec,
		LocalDateTime recordedAt, Visibility visibility) {
		this.userId = userId;
		this.gridId = gridId;
		this.originalS3Key = originalS3Key;
		this.geom = geom;
		this.durationSec = durationSec;
		this.recordedAt = recordedAt;
		this.processingStatus = ProcessingStatus.UPLOADED;
		this.visibility = visibility;
		this.status = VideoStatus.ACTIVE;
		this.viewCount = 0L;
		// DB TIMESTAMP 정밀도(µs)로 절단 — 리눅스 나노초 시계에서 재조회 값과 어긋나지 않게
		this.createdAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	/** visibility 는 받은 값을 저장만 한다 — "미지정은 PUBLIC" 기본값 결정은 제품 결정이라 서비스 몫이다 (MSG-204). */
	public static Video create(Long userId, String gridId, String originalS3Key, Point geom, Short durationSec,
		LocalDateTime recordedAt, Visibility visibility) {
		return new Video(userId, gridId, originalS3Key, geom, durationSec, recordedAt, visibility);
	}

	/** UPLOADED → ENCODING (MSG-65 워커 진입). */
	public void markEncoding() {
		this.processingStatus = ProcessingStatus.ENCODING;
	}

	/** ENCODING → READY. 인자는 URL 이 아니라 S3 key 다 (encodedUrl 필드 주석 참조). */
	public void markReady(String encodedKey, String thumbnailKey) {
		this.encodedUrl = encodedKey;
		this.thumbnailUrl = thumbnailKey;
		this.processingStatus = ProcessingStatus.READY;
	}

	/**
	 * ENCODING → BLURRING (MSG-149). AI 활성 흐름에서 인코딩이 끝나면 READY 대신 여기로 온다.
	 * **thumbnailUrl 은 세팅하지 않는다** — AI 활성 인코딩은 미블러 썸네일을 S3 에 올리지 않으므로(P1),
	 * "thumbnailUrl non-null ⇔ S3 객체 실존" 불변식을 지키려면 BLURRING 동안 null 이어야 한다. 썸네일 키는
	 * 폴러가 완료 시 블러본에서 뽑아 올린 뒤 markReadyFromBlurring 에서 기록한다.
	 * 인자는 URL 이 아니라 S3 key 다 (encodedUrl 필드 주석 참조).
	 */
	public void markEncoded(String encodedKey) {
		this.encodedUrl = encodedKey;
		this.processingStatus = ProcessingStatus.BLURRING;
		this.blurringStartedAt = LocalDateTime.now(ZoneOffset.UTC);
	}

	/**
	 * BLURRING → READY (MSG-150). 블러본·하이라이트는 applyBlurResult 로 이미 채운 뒤 전이한다.
	 * thumbnailUrl 은 폴러가 블러본 썸네일을 S3 에 올린 **직후** 여기서 기록한다 — "객체 업로드 완료 후에만
	 * 키 기록" 순서로 불변식을 지킨다. encoded 키는 유지한다.
	 */
	public void markReadyFromBlurring(String thumbnailKey) {
		this.thumbnailUrl = thumbnailKey;
		this.processingStatus = ProcessingStatus.READY;
	}

	/** AI job_id 상관키 기록 (MSG-149). 폴러가 제출 직후 호출해 재시작 복구·폴링에 쓴다. */
	public void recordAiJob(String jobId) {
		this.aiJobId = jobId;
	}

	/**
	 * 폴링 404(잡 유실) 시 미제출로 복귀 (MSG-283). aiJobId 만 비워 폴러의 미제출 경로가 다음 주기에
	 * 재제출하게 한다. blurringStartedAt 은 시도 넌스라 유지한다 — 갱신하면 AI 재시작마다 타임아웃 창이
	 * 리셋되고, 재제출 recordAiJob 의 startedAt 일치 가드도 깨진다 (재제출은 새 시도가 아니라 같은 시도의 계속).
	 */
	public void clearAiJob() {
		this.aiJobId = null;
	}

	/** 변환 실패. 재시도는 없고 기록만 남긴다 (MSG-65 D8). 사유 없는 실패(인코딩·타임아웃)는 이쪽. */
	public void markFailed() {
		markFailed(null);
	}

	/** 사유 있는 실패 (MSG-286 프리체크 탈락). */
	public void markFailed(String failReason) {
		this.failReason = failReason;
		this.processingStatus = ProcessingStatus.FAILED;
	}

	/**
	 * 파일 교체 (MSG-71 D2 정책 B). row 를 유지하고 파일 참조만 갈아끼운다 —
	 * id 가 그대로라 user_grids 의 video_count·cover_video_id 가 자연히 불변이다.
	 *
	 * 인코딩 결과는 새 파일 기준으로 다시 만들어야 하므로 비우고 UPLOADED 로 되돌린다(D6).
	 * 그러지 않으면 재인코딩 전까지 옛 영상이 재생된다. AI 블러 결과(블러본·하이라이트·job_id)도
	 * 원본에서 파생된 값이라 원본이 바뀌면 전부 무효 → 함께 비운다 (MSG-145).
	 */
	public void replaceFile(String originalS3Key, Short durationSec, LocalDateTime recordedAt) {
		this.originalS3Key = originalS3Key;
		this.durationSec = durationSec;
		this.recordedAt = recordedAt;
		this.encodedUrl = null;
		this.thumbnailUrl = null;
		this.blurredS3Key = null;
		this.highlights = null;
		this.aiJobId = null;
		this.blurringStartedAt = null;
		this.failReason = null;   // 사유도 원본 파생 값 — 새 시도에 옛 탈락 사유가 남으면 안 된다 (MSG-286)
		this.processingStatus = ProcessingStatus.UPLOADED;
	}

	/**
	 * AI 블러 결과 반영 (MSG-145). 블러본 S3 key 와 하이라이트 구간을 채운다.
	 * 상태 전이(BLURRING → READY)는 하지 않는다 — 그 오케스트레이션은 MSG-150 소관이다.
	 */
	public void applyBlurResult(String blurredS3Key, List<List<Double>> highlights) {
		this.blurredS3Key = blurredS3Key;
		this.highlights = highlights;
	}

	/**
	 * 블러 꺼짐 경로의 후행 하이라이트 저장 (MSG-456 D-1). applyBlurResult 는 블러본 키를 함께 세팅하므로
	 * 재사용하지 않는다 — 여기서는 하이라이트만 채우고 상태·키는 건드리지 않는다.
	 */
	public void recordHighlights(List<List<Double>> highlights) {
		this.highlights = highlights;
	}

	/** 공개 범위 전환 (MSG-162). 노출 조건(READY)은 read 경로가 게이트하므로 여기선 값만 바꾼다. */
	public void changeVisibility(Visibility visibility) {
		this.visibility = visibility;
	}

	/** soft delete (MSG-72 D2). row 만 표시하고, S3 파일은 서비스가 커밋 후 지운다 (MSG-133). */
	public void markDeleted() {
		this.status = VideoStatus.DELETED;
	}

	/** ACTIVE → BLINDED (MSG-193). 전이 가드는 VideoModerationService 가 잠금 아래에서 수행한다. */
	public void markBlinded() {
		this.status = VideoStatus.BLINDED;
	}

	/** BLINDED → ACTIVE 해제 (MSG-193). */
	public void markActive() {
		this.status = VideoStatus.ACTIVE;
	}

	public boolean isDeleted() {
		return this.status == VideoStatus.DELETED;
	}
}

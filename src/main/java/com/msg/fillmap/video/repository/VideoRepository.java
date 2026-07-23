package com.msg.fillmap.video.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;

public interface VideoRepository extends JpaRepository<Video, Long> {

	/**
	 * 격자별 내 영상 리스트 (MSG-127). user_id 로 개인 도감을 격리하고, status=ACTIVE 로
	 * 삭제(DELETED)·블라인드(BLINDED)를 제외한다. 최근 업로드(방문)가 먼저 오도록 created_at DESC.
	 * visibility·processing_status 는 필터하지 않는다 — 내 도감이라 PRIVATE·인코딩 중 영상도 보여야 한다.
	 */
	List<Video> findByUserIdAndGridIdAndStatusOrderByCreatedAtDesc(Long userId, String gridId, VideoStatus status);

	/**
	 * AI 폴러가 처리할 대상 조회 (MSG-149 D3, P2). status=ACTIVE 로 한정해 삭제(DELETED)된 영상을 제외한다 —
	 * 삭제 후에도 processing_status 는 BLURRING 으로 남으므로, 이 필터가 없으면 폴러가 삭제본을 계속 집어
	 * 블러본을 S3 에 새로 올려 영구 고아를 만든다. 상태를 DB 에 두므로 서버 재시작 후에도 in-flight 영상이
	 * 그대로 잡혀 이어 처리된다 — in-memory async 루프였다면 재시작 시 소실됐을 것이다.
	 */
	List<Video> findByStatusAndProcessingStatus(VideoStatus status, ProcessingStatus processingStatus);

	/**
	 * 행 잠금 조회 (MSG-149 P1-c). 폴러의 블러 결과/실패/제출 쓰기 가드가 읽기-후-쓰기 레이스(ms 창)를
	 * 없애기 위해 SELECT ... FOR UPDATE 로 잠근다 — replaceVideo/deleteVideo 트랜잭션의 UPDATE 와 같은 행에서
	 * 직렬화되어, 폴러가 잠금 후 재확인한 상태가 그 트랜잭션 커밋 이후 상태임을 보장한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select v from Video v where v.id = :id")
	Optional<Video> findWithLockById(@Param("id") Long id);

	/**
	 * 격자 전역 대표 영상 1건 (MSG-87). user_id 조건 없이 그 격자의 모든 공개·READY 영상 중 조회수 →
	 * 최신 순으로 1건을 뽑는다 — MSG-127 의 개인 격리와 정반대 축(전역, 본인 포함)이다.
	 * WHERE·ORDER BY 는 idx_videos_grid_popular 부분 인덱스(V1__init.sql:110)와 바이트 단위로 일치시켜
	 * 플래너가 인덱스만으로 LIMIT 1 을 만족하게 한다 — 인덱스가 대표 선정 정책의 단일 진실 원천이다.
	 */
	@Query(value = """
		SELECT * FROM videos
		WHERE grid_id = :gridId
		  AND status = 'ACTIVE' AND visibility = 'PUBLIC' AND processing_status = 'READY'
		ORDER BY view_count DESC, created_at DESC
		LIMIT 1
		""", nativeQuery = true)
	Optional<Video> findGlobalCover(@Param("gridId") String gridId);

	/**
	 * 격자 lazy insert (전역 격자 등록). 이미 있으면 no-op — 멱등.
	 * center/bbox 는 GridEncoder 산출값을 PostGIS geography 로 변환해 저장한다.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
		VALUES (
			:gridId, :gridY, :gridX,
			ST_SetSRID(ST_MakePoint(:centerLon, :centerLat), 4326)::geography,
			ST_SetSRID(ST_GeomFromText(:bboxWkt), 4326)::geography
		)
		ON CONFLICT (grid_id) DO NOTHING
		""", nativeQuery = true)
	void upsertGrid(
		@Param("gridId") String gridId,
		@Param("gridY") long gridY,
		@Param("gridX") long gridX,
		@Param("centerLat") double centerLat,
		@Param("centerLon") double centerLon,
		@Param("bboxWkt") String bboxWkt
	);

	/**
	 * 점령 여부 판정 (upsert 전 호출). false 면 이번 업로드가 첫 점령.
	 */
	@Query(value = "SELECT EXISTS(SELECT 1 FROM user_grids WHERE user_id = :userId AND grid_id = :gridId)",
		nativeQuery = true)
	boolean existsUserGrid(@Param("userId") long userId, @Param("gridId") String gridId);

	/**
	 * 점령 UPSERT. 첫 방문이면 INSERT(video_count=1), 재방문이면 video_count+1 + last_uploaded_at 갱신.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO user_grids (user_id, grid_id, video_count, cover_video_id, first_collected_at, last_uploaded_at)
		VALUES (:userId, :gridId, 1, :coverVideoId, now(), now())
		ON CONFLICT (user_id, grid_id) DO UPDATE
			SET video_count = user_grids.video_count + 1,
			    last_uploaded_at = now()
		""", nativeQuery = true)
	void upsertUserGrid(
		@Param("userId") long userId,
		@Param("gridId") String gridId,
		@Param("coverVideoId") long coverVideoId
	);

	/**
	 * 같은 원본을 두 번 확정하는 걸 막는다 (MSG-132). DB 의 UNIQUE 제약이 최종 방어선이고,
	 * 이 조회는 그 전에 걸러 500 대신 4xx 를 주기 위한 것이다.
	 */
	boolean existsByOriginalS3Key(String originalS3Key);

	/* ---------- MSG-72 삭제 · 점령 롤백 ---------- */

	/** 소유권 검증용. 없으면 NOT_FOUND, 있는데 주인이 다르면 FORBIDDEN 을 구분하려고 id 조회와 나눠 쓴다. */
	Optional<Video> findByIdAndUserId(Long id, Long userId);

	/**
	 * 점령 카운트 원자적 감소 (MSG-72 D3). 읽고 계산해서 쓰지 않으므로 동시 삭제에도 어긋나지 않는다.
	 * GREATEST 로 음수를 방어한다.
	 */
	@Modifying
	@Query(value = """
		UPDATE user_grids
		SET video_count = GREATEST(video_count - 1, 0)
		WHERE user_id = :userId AND grid_id = :gridId
		""", nativeQuery = true)
	void decrementVideoCount(@Param("userId") long userId, @Param("gridId") String gridId);

	/**
	 * 점령 롤백 — 그 격자의 내 영상이 모두 사라졌을 때만 도감에서 제거한다 (grids row 는 유지, D5).
	 * 조건을 SQL 안에 두어 "카운트를 읽고 → 0인지 보고 → 지운다" 사이의 race 를 없앤다.
	 * 반환값은 삭제된 행 수 — 1 이면 롤백됨, 0 이면 아직 점령 중이다.
	 */
	@Modifying
	@Query(value = """
		DELETE FROM user_grids
		WHERE user_id = :userId AND grid_id = :gridId AND video_count = 0
		""", nativeQuery = true)
	int deleteUserGridIfEmpty(@Param("userId") long userId, @Param("gridId") String gridId);

	/**
	 * cover 재선정 (D4) — 남은 ACTIVE 영상 중 가장 오래된 것(최초 수집)을 고른다.
	 * soft delete 라 videos row 가 남으므로 FK 의 ON DELETE SET NULL 이 동작하지 않는다. 직접 갱신해야 한다.
	 * 남은 영상이 없으면 서브쿼리가 NULL 을 주고, 컬럼이 nullable 이라 그대로 비워진다.
	 */
	@Modifying
	@Query(value = """
		UPDATE user_grids ug
		SET cover_video_id = (
			SELECT v.id FROM videos v
			WHERE v.user_id = :userId AND v.grid_id = :gridId AND v.status = 'ACTIVE'
			ORDER BY v.created_at, v.id
			LIMIT 1
		)
		WHERE ug.user_id = :userId AND ug.grid_id = :gridId AND ug.cover_video_id = :deletedVideoId
		""", nativeQuery = true)
	void reselectCover(
		@Param("userId") long userId,
		@Param("gridId") String gridId,
		@Param("deletedVideoId") long deletedVideoId
	);

	/* ---------- MSG-206 재생 조회수 증가 ---------- */

	/**
	 * 재생 조회수 원자적 증가 (MSG-206 D5). 읽고 계산해서 쓰지 않으므로 동시 재생에도 lost update 가 없다
	 * (decrementVideoCount 관용과 동일). chk_videos_view_count(view_count >= 0) 는 증가라 항상 만족한다.
	 */
	@Modifying
	@Query(value = "UPDATE videos SET view_count = view_count + 1 WHERE id = :id", nativeQuery = true)
	void incrementViewCount(@Param("id") long id);
}

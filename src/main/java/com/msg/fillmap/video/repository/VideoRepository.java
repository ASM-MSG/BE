package com.msg.fillmap.video.repository;

import java.time.LocalDateTime;
import java.util.Collection;
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
	 * 친구 격자 영상 목록 (MSG-187 D5). 친구 판정은 호출자(friend 도메인) 책임 — 이 쿼리는
	 * "친구에게 허용된 공개범위"만 거른다. PRIVATE 제외(FR-4), ACTIVE 로 DELETED·BLINDED 제외 +
	 * READY 로 인코딩 미완 제외(FR-9) — 목록의 전 항목이 MSG-285 재생 판정을 통과한다(FR-5).
	 */
	@Query("""
		SELECT v FROM Video v
		WHERE v.userId = :ownerUserId AND v.gridId = :gridId
			AND v.status = com.msg.fillmap.video.entity.VideoStatus.ACTIVE
			AND v.processingStatus = com.msg.fillmap.video.entity.ProcessingStatus.READY
			AND v.visibility IN (com.msg.fillmap.video.entity.Visibility.PUBLIC,
				com.msg.fillmap.video.entity.Visibility.FRIENDS)
		ORDER BY v.createdAt DESC, v.id DESC
		""")
	List<Video> findFriendGridVideos(@Param("ownerUserId") Long ownerUserId, @Param("gridId") String gridId);

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
	 * 삭제 경로(MSG-243)도 이 잠금으로 ACTIVE→DELETED 전이를 직렬화해 video_count 이중 감소를 막는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select v from Video v where v.id = :id")
	Optional<Video> findWithLockById(@Param("id") Long id);

	/**
	 * 알림 잠금 순서 선취용 무잠금 선독 (MSG-313 Codex 2R) — user_id 는 불변이라 행 잠금 전에 읽어도
	 * 안전하다. 빈 결과 = 탈퇴 CASCADE 로 이미 삭제된 영상이라 호출자가 전이를 스킵한다.
	 */
	@Query("SELECT v.userId FROM Video v WHERE v.id = :id")
	Optional<Long> findUserIdById(@Param("id") Long id);

	/**
	 * 격자 전역 대표 영상 1건 (MSG-87). user_id 조건 없이 그 격자의 모든 공개·READY 영상 중 조회수 →
	 * 최신 → id 순으로 1건을 뽑는다 — MSG-127 의 개인 격리와 정반대 축(전역, 본인 포함)이다.
	 * WHERE·ORDER BY 는 idx_videos_grid_popular 부분 인덱스(V1__init.sql:110)와 바이트 단위로 일치시켜
	 * 플래너가 인덱스만으로 LIMIT 1 을 만족하게 한다 — 인덱스가 대표 선정 정책의 단일 진실 원천이다.
	 * id DESC 는 (조회수·시각) 완전 동률 타이브레이크(MSG-238 §D7) — 탐색 카드 커버(findExploreGrids*)와
	 * 3키로 정합해 화면 간 대표가 항상 같다. 동률 그룹만 미니 정렬이라 인덱스 재정의 불요.
	 */
	@Query(value = """
		SELECT * FROM videos
		WHERE grid_id = :gridId
		  AND status = 'ACTIVE' AND visibility = 'PUBLIC' AND processing_status = 'READY'
		ORDER BY view_count DESC, created_at DESC, id DESC
		LIMIT 1
		""", nativeQuery = true)
	Optional<Video> findGlobalCover(@Param("gridId") String gridId);

	/**
	 * 격자 전역 영상 목록 첫 페이지 (MSG-237). findGlobalCover 와 같은 후보 집합(전역·PUBLIC·READY·ACTIVE,
	 * 본인 포함)을 LIMIT 1 → 페이지 윈도우로 넓힌 것이다. WHERE·ORDER BY 앞 두 키는 idx_videos_grid_popular
	 * 부분 인덱스(V1__init.sql:110)와 바이트 단위로 일치시켜 인덱스 range scan 으로 페이지를 만족하게 한다.
	 * id DESC 는 인덱스에 없는 동률 타이브레이크 — (view_count, created_at) 까지 같은 행의 페이지 간 순서를
	 * 결정적으로 만들며, 동률 그룹 국소 정렬이라 단일 격자 소량 규모에 무해하다(§D2).
	 * limit 은 서비스가 size+1 lookahead 로 넘기고 hasNext 판정도 서비스 소관이다.
	 */
	@Query(value = """
		SELECT * FROM videos
		WHERE grid_id = :gridId
		  AND status = 'ACTIVE' AND visibility = 'PUBLIC' AND processing_status = 'READY'
		ORDER BY view_count DESC, created_at DESC, id DESC
		LIMIT :limit
		""", nativeQuery = true)
	List<Video> findGlobalVideos(@Param("gridId") String gridId, @Param("limit") int limit);

	/**
	 * 전역 목록 커서 이후 페이지 (MSG-237 §D2 keyset). 직전 페이지 마지막 항목의 경계값
	 * (view_count, created_at, id) 보다 정렬상 뒤인 행만 PostgreSQL row-value 비교(DESC 등가 <)로 이어서
	 * 반환한다 — GridRepository.findOccupiedPageAfter(MSG-90) 와 같은 패턴이다. 커서 유무로 메서드를
	 * 나눈 것도 같은 이유다(null 가드 단일 쿼리의 native 파라미터 타입 추론 문제 회피).
	 */
	@Query(value = """
		SELECT * FROM videos
		WHERE grid_id = :gridId
		  AND status = 'ACTIVE' AND visibility = 'PUBLIC' AND processing_status = 'READY'
		  AND (view_count, created_at, id) < (:cursorViewCount, :cursorCreatedAt, :cursorId)
		ORDER BY view_count DESC, created_at DESC, id DESC
		LIMIT :limit
		""", nativeQuery = true)
	List<Video> findGlobalVideosAfter(
		@Param("gridId") String gridId,
		@Param("cursorViewCount") long cursorViewCount,
		@Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
		@Param("cursorId") long cursorId,
		@Param("limit") int limit
	);

	/**
	 * 전역 노출 응답의 작성자 닉네임 (MSG-371). 단건 경로(대표·재생)용 — 영상을 고른 뒤 같은 트랜잭션에서
	 * 1회 더 읽는다. 위 native 조회 3종에 users 조인을 넣지 않는 이유가 여기 있다: 조인은 엔티티 반환을
	 * 프로젝션으로 바꿔 커서·presign·DTO 팩토리까지 연쇄 재작성을 부르고, idx_videos_grid_popular 부분
	 * 인덱스와의 바이트 단위 일치도 흔들린다. 매 요청 users 를 읽는 구조라 닉네임 변경이 다음 조회에
	 * 그대로 반영된다(비정규화 사본 없음). 쿼리를 user 가 아닌 video 쪽에 두는 것은 "쿼리 소속은 용도
	 * 기준" 관례다(UserRepository.findAllS3KeysByUserId 선례). 탈퇴 CASCADE 로 정상 경로엔 empty 가 없다 —
	 * empty 는 두 문장 사이에 탈퇴가 커밋돼 그 영상이 방금 사라졌다는 뜻이고, 호출자는 응답에서 숨긴다
	 * (대표는 대표 없음, 재생은 404). null 닉네임을 응답에 싣는 경로는 없다.
	 */
	@Query("SELECT u.nickname FROM User u WHERE u.id = :userId")
	Optional<String> findAuthorNickname(@Param("userId") Long userId);

	/**
	 * 전역 목록 페이지의 작성자 닉네임 배치 (MSG-371). 중복 제거한 작성자 id 로 IN 1회 — 항목 수와 무관하게
	 * 왕복이 상수라 페이지가 커져도 조회가 늘지 않는다(N+1 금지). 호출자가 빈 컬렉션을 넘기지 않는다.
	 */
	@Query("SELECT u.id AS userId, u.nickname AS nickname FROM User u WHERE u.id IN :userIds")
	List<AuthorNicknameProjection> findAuthorNicknames(@Param("userIds") Collection<Long> userIds);

	/**
	 * 격자 lazy insert (전역 격자 등록). 이미 있으면 no-op — 멱등.
	 * center/bbox 는 GridEncoder 산출값을 PostGIS geography 로 변환해 저장한다.
	 *
	 * region_code (MSG-167 §D1): 격자 중심점이 속한 행정동을 신규 격자 INSERT 시 1회 판정해 함께 채운다.
	 * 판정 규칙은 RegionRepository.findContainingRegion(93)·refreshRegionStats(155)·V5 백필과 동일하다
	 * (ST_Covers(boundary_geom, 중심점) ORDER BY region_code LIMIT 1) — 저장 라벨이 by-grid 귀속·recompute
	 * 귀속과 항상 같은 행정동이다. VALUES 대신 SELECT … WHERE NOT EXISTS 로 두어 재방문(격자 존재) 시엔
	 * 서브쿼리가 평가되지 않게 한다 — 판정은 격자 생애 1회, 무귀속(해안)이면 NULL. ON CONFLICT 는 동시 삽입
	 * 레이스 방어로 유지한다.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom, region_code)
		SELECT
			:gridId, :gridY, :gridX,
			ST_SetSRID(ST_MakePoint(:centerLon, :centerLat), 4326)::geography,
			ST_SetSRID(ST_GeomFromText(:bboxWkt), 4326)::geography,
			(SELECT r.region_code FROM regions r
				WHERE ST_Covers(r.boundary_geom, ST_SetSRID(ST_MakePoint(:centerLon, :centerLat), 4326)::geography)
				ORDER BY r.region_code
				LIMIT 1)
		WHERE NOT EXISTS (SELECT 1 FROM grids WHERE grid_id = :gridId)
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
	 * 격자 저장 라벨(grids.region_code)의 행정동 이름 (MSG-341 D-6). 업로드 확정·재생 응답의 regionName —
	 * 구역(zone) 밖 격자에서 FE 가 쓰는 폴백 라벨 재료다. upsertGrid 는 region_code 판정·저장까지만 하고
	 * 이름을 돌려주지 않으므로 확정 직후 이 변환 조회 1회가 따로 필요하다(신규 격자도 같은 트랜잭션에서
	 * 방금 INSERT 한 라벨을 읽는다). 좌표 재판정(resolveByPoint)이 아니라 저장 라벨을 읽는 이유는 도감
	 * 목록·카드 리스트의 regionName 과 같은 축이어야 경계 격자에서 화면 간 이름이 어긋나지 않아서다.
	 * grids row 없음(이론상 도달 불가) 또는 region_code NULL(무귀속·해상)이면 empty → 응답은 null.
	 */
	@Query(value = """
		SELECT r.region_name FROM grids g
		JOIN regions r ON r.region_code = g.region_code
		WHERE g.grid_id = :gridId
		""", nativeQuery = true)
	Optional<String> findRegionNameByGridId(@Param("gridId") String gridId);

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
	 * 같은 원본을 두 번 확정하는 걸 막는다 (MSG-132). MSG-247 2R 부터 original 키는 시도별 발급이라
	 * 이 정확 매치는 구형(pending 결정 파생) 키의 재확정 차단용으로만 남는다 — 신형은 아래 prefix 매치.
	 */
	boolean existsByOriginalS3Key(String originalS3Key);

	/**
	 * 이 pending 에서 파생된 확정이 이미 있는지 — 시도별 키(pendingStem + "-" + 시도 UUID) 대응 (MSG-247 2R).
	 * original 키가 시도마다 달라 정확 매치·UNIQUE 로는 이중 확정을 못 잡으므로 pendingStem prefix 로 판정한다.
	 */
	boolean existsByOriginalS3KeyStartingWith(String originalS3KeyPrefix);

	/**
	 * 확정을 pending 키 단위로 직렬화한다 (MSG-247 2R). 시도별 original 키는 UNIQUE 제약의 동시 이중 확정
	 * 직렬화를 무력화하므로, 같은 pending 의 동시 확정 2건이 둘 다 exists 검사를 통과(미커밋 불가시)해
	 * 영상 2개가 되는 걸 이 락이 막는다 — 락 획득자는 앞 확정의 커밋/롤백 이후에만 진입해 커밋을 본다.
	 * 트랜잭션 종료 시 자동 해제(xact lock). 키는 'video_confirm:' 접두로 다른 advisory 사용처와 분리.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended('video_confirm:' || :pendingKey, 0))",
		nativeQuery = true)
	Object acquirePendingKeyConfirmLock(@Param("pendingKey") String pendingKey);

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

	/* ---------- MSG-238 전역 탐색 (행정동 축) ---------- */

	/**
	 * 행정동 격자 카드 — 인기순 (MSG-238 §도메인 1). 저장 라벨(grids.region_code) equi 로 그 행정동 격자만
	 * 구동(idx_grids_region_code, V7)하고, 격자당 LATERAL 2회가 idx_videos_grid_popular 부분 인덱스를
	 * prefix 프로브한다 — 게이트 프래그먼트는 그 부분 인덱스 조건과 바이트 단위 일치(87/237 원칙)가 정본이다.
	 * agg.video_count > 0 이 §D1(게이트 통과 영상 ≥1 격자만)을 강제하고, 커버 LATERAL 은 findGlobalCover(87)와
	 * 같은 3키(조회수→최신→id)라 카드 커버 = 격자 상세 대표가 항상 같은 영상이다(§D7). 인기 = 조회수 합 —
	 * 동률은 영상 수 → grid_id 내림차순으로 결정적(§D3). 무라벨(해안) 격자는 equi 에 자연 탈락.
	 * limit null 은 LIMIT NULL(전부) — 페이지네이션 미도입(§D4), CAST 가 null 파라미터의 타입을 고정한다.
	 * native ORDER BY 분기 불가라 최신순과 메서드를 분리한다(90 선례).
	 */
	@Query(value = """
		SELECT
			g.grid_id           AS "gridId",
			g.grid_y            AS "gridY",
			g.grid_x            AS "gridX",
			agg.video_count     AS "videoCount",
			cover.thumbnail_url AS "coverThumbnailKey",
			cover.duration_sec  AS "coverDurationSec"
		FROM grids g
		JOIN LATERAL (
			SELECT COUNT(*) AS video_count,
			       SUM(v.view_count) AS view_sum,
			       MAX(v.created_at) AS latest_at
			FROM videos v
			WHERE v.grid_id = g.grid_id
			  AND v.status = 'ACTIVE' AND v.visibility = 'PUBLIC' AND v.processing_status = 'READY'
		) agg ON agg.video_count > 0
		JOIN LATERAL (
			SELECT v.thumbnail_url, v.duration_sec
			FROM videos v
			WHERE v.grid_id = g.grid_id
			  AND v.status = 'ACTIVE' AND v.visibility = 'PUBLIC' AND v.processing_status = 'READY'
			ORDER BY v.view_count DESC, v.created_at DESC, v.id DESC
			LIMIT 1
		) cover ON TRUE
		WHERE g.region_code = :regionCode
		ORDER BY agg.view_sum DESC, agg.video_count DESC, g.grid_id DESC
		LIMIT CAST(:limit AS bigint)
		""", nativeQuery = true)
	List<ExploreGridProjection> findExploreGridsPopular(
		@Param("regionCode") String regionCode, @Param("limit") Integer limit);

	/**
	 * 행정동 격자 카드 — 최신순 (MSG-238 §도메인 1). 인기순과 동일 몸통(게이트·커버·§D1)에 ORDER BY 만
	 * 최신 공개 영상 시각(MAX created_at) 내림차순 — 동률은 grid_id 내림차순(§D3). 개인 축
	 * user_grids.last_uploaded_at 은 사용자별 행이라 전역 축에 못 쓴다 — 게이트 통과 영상의 MAX 가 정본.
	 */
	@Query(value = """
		SELECT
			g.grid_id           AS "gridId",
			g.grid_y            AS "gridY",
			g.grid_x            AS "gridX",
			agg.video_count     AS "videoCount",
			cover.thumbnail_url AS "coverThumbnailKey",
			cover.duration_sec  AS "coverDurationSec"
		FROM grids g
		JOIN LATERAL (
			SELECT COUNT(*) AS video_count,
			       SUM(v.view_count) AS view_sum,
			       MAX(v.created_at) AS latest_at
			FROM videos v
			WHERE v.grid_id = g.grid_id
			  AND v.status = 'ACTIVE' AND v.visibility = 'PUBLIC' AND v.processing_status = 'READY'
		) agg ON agg.video_count > 0
		JOIN LATERAL (
			SELECT v.thumbnail_url, v.duration_sec
			FROM videos v
			WHERE v.grid_id = g.grid_id
			  AND v.status = 'ACTIVE' AND v.visibility = 'PUBLIC' AND v.processing_status = 'READY'
			ORDER BY v.view_count DESC, v.created_at DESC, v.id DESC
			LIMIT 1
		) cover ON TRUE
		WHERE g.region_code = :regionCode
		ORDER BY agg.latest_at DESC, g.grid_id DESC
		LIMIT CAST(:limit AS bigint)
		""", nativeQuery = true)
	List<ExploreGridProjection> findExploreGridsLatest(
		@Param("regionCode") String regionCode, @Param("limit") Integer limit);

	/**
	 * 행정동 헤더 카운트 + regionName (MSG-238 §도메인 2, 1왕복). 게이트·귀속(g.region_code equi)이 카드
	 * 쿼리와 동일해 gridCount = 카드 집합 크기, videoCount = 카드 videoCount 합이 정의 차원에서 강제된다
	 * (§D1 — limit 무관 전체 기준). LEFT JOIN + COUNT(v.id) 라 실존 region 콘텐츠 0 은 (regionName, 0, 0)
	 * 1행으로 합성되고, 미존재 region 은 0행 — 서비스가 regionName null·0·빈 배열로 합성한다(§D2, 6404 아님).
	 */
	@Query(value = """
		SELECT
			r.region_name                  AS "regionName",
			COUNT(DISTINCT v.grid_id)::int AS "gridCount",
			COUNT(v.id)                    AS "videoCount"
		FROM regions r
		LEFT JOIN grids g  ON g.region_code = r.region_code
		LEFT JOIN videos v ON v.grid_id = g.grid_id
			AND v.status = 'ACTIVE' AND v.visibility = 'PUBLIC' AND v.processing_status = 'READY'
		WHERE r.region_code = :regionCode
		GROUP BY r.region_name
		""", nativeQuery = true)
	Optional<RegionExploreSummaryProjection> getRegionExploreSummary(@Param("regionCode") String regionCode);

	/**
	 * 전체 지역 리스트 — 행정동별 게이트 통과 격자 수 (MSG-238 §도메인 3). 게이트 통과 영상 ≥1 격자만
	 * EXISTS(부분 인덱스 prefix LIMIT 1 프로브)로 세고, regions JOIN 에서 무라벨(NULL) 격자가 자연 탈락한다.
	 * gridCount 는 §D1 동일 정의라 ①의 카드 집합 크기와 항상 일치. 콘텐츠 많은 지역 먼저
	 * (gridCount DESC, region_code ASC — §D3 결정적), 행 상한 = 콘텐츠 있는 행정동 수 ≤ 3,558 이라 no-LIMIT.
	 * grids 전수 스캔 — lazy insert 라 상한이 전역 등록 격자 총수(§D6 ceiling 참조, MVP 소량).
	 */
	@Query(value = """
		SELECT
			g.region_code AS "regionCode",
			r.region_name AS "regionName",
			COUNT(*)::int AS "gridCount"
		FROM grids g
		JOIN regions r ON r.region_code = g.region_code
		WHERE EXISTS (
			SELECT 1 FROM videos v
			WHERE v.grid_id = g.grid_id
			  AND v.status = 'ACTIVE' AND v.visibility = 'PUBLIC' AND v.processing_status = 'READY')
		GROUP BY g.region_code, r.region_name
		ORDER BY COUNT(*) DESC, g.region_code
		""", nativeQuery = true)
	List<RegionGridCountProjection> getRegionGridCounts();
}

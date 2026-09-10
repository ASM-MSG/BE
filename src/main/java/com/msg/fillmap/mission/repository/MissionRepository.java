package com.msg.fillmap.mission.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.mission.entity.Mission;

/**
 * 미션 조회 리포지토리 (MSG-222 활성 조회 + MSG-223 판정). 활성 판정만 담당하고 mission_grids 는
 * MissionGridRepository 로 분리해 2쿼리로 N+1 을 피한다(§도메인 1). 판정 2종(후보 역조회·단일 판정)은
 * 업로드 확정 훅 전용 native 다 — 조회 캐시를 읽지 않고 DB 직행(MSG-223 §구현 구성).
 */
public interface MissionRepository extends JpaRepository<Mission, Long> {

	/**
	 * 활성 미션 조회 (§도메인 2). start_at/end_at 을 각 경계 독립으로 판정한다 — NULL 은 상시로 해석하되
	 * start 전 미래 이벤트는 노출하지 않는다. 양쪽 NULL(코스·지속)은 항상 활성.
	 * ORDER BY m.id 는 표시 정렬이 아니라 결정성 확보다 (MSG-398 D12) — 없으면 스냅샷이 갱신될 때마다
	 * 목록 카드가 이유 없이 뒤바뀐다. findBySource·findCompleted 가 같은 이유로 id 정렬을 걸어 두었다.
	 * hidden_at 술어(MSG-500 D-3)는 노출 중지된 승인 미션을 뺀다 — 시드 미션은 전부 NULL 이라 동작 불변이다.
	 */
	@Query("""
		SELECT m FROM Mission m
		WHERE m.hiddenAt IS NULL
		  AND (m.startAt IS NULL OR m.startAt <= :now)
		  AND (m.endAt IS NULL OR :now <= m.endAt)
		ORDER BY m.id
		""")
	List<Mission> findActive(@Param("now") LocalDateTime now);

	/**
	 * 미션별 내 진행도·스탬프 (MSG-398 D8·D9). 채운 칸 = 미션 기간 안에 촬영한 내 영상이 있는 서로 다른
	 * 격자 수 — videos 조인 조건은 판정 쿼리 findCompleted 와 글자 단위로 같다(2026-08-15 성민 확정으로
	 * user_grids 교집합 정의를 대체 — 도감 점령은 촬영 시각을 안 봐 "다 채웠는데 완료가 아닌" 카드가
	 * 그려진다). 다른 점은 둘뿐이다: INNER 가 아니라 LEFT(미진행 미션도 0으로 나온다), HAVING 임계 판정
	 * 대신 LEAST 로 값 자체를 내린다(81칸 축제 목표 1 → "5/1" 방지). 행사 영상 안티조인(NOT EXISTS
	 * event_videos)도 그 글자 단위 동일 계약의 일부다 — 행사 영상은 현장에 없어도 올릴 수 있어 격자 방문
	 * 증거가 아니므로 미션에서 제외한다(MSG-450). LEFT JOIN 이라 ON 절에 둔다: WHERE 로 내리면 미진행
	 * 미션 행이 통째로 사라져 0 진행도가 안 나온다. DISTINCT 는 필수다 — 같은 격자의 재방문 영상이
	 * videos 조인을 팬아웃시킨다. user_missions LEFT JOIN 은 PK 로 미션당 많아야 한 행이라
	 * 행 수를 늘리지 않고, 복제된 행에 같은 값이 붙어도 BOOL_OR 라 안전하다(D9). 활성 조건이 없다 —
	 * 기간이 끝난 미션도 진행도가 나온다(D11). 존재하지 않는 id 는 missions 에서 출발하므로 행이 안 생겨
	 * 자연히 빠진다. 받치는 인덱스는 findCompleted 와 같은 idx_videos_user_created 의 user_id 선두부다.
	 */
	@Query(value = """
		SELECT m.id AS "missionId",
			m.target_count AS "targetCount",
			LEAST(COUNT(DISTINCT v.grid_id), m.target_count) AS "filledCount",
			BOOL_OR(um.user_id IS NOT NULL) AS "completed"
		FROM missions m
		LEFT JOIN mission_grids mg ON mg.mission_id = m.id
		LEFT JOIN videos v ON v.grid_id = mg.grid_id
			AND v.user_id = :userId
			AND v.status <> 'DELETED'
			AND NOT EXISTS (SELECT 1 FROM event_videos ev WHERE ev.video_id = v.id)
			AND (m.start_at IS NULL OR v.recorded_at >= m.start_at)
			AND (m.end_at IS NULL OR v.recorded_at <= m.end_at)
		LEFT JOIN user_missions um ON um.user_id = :userId AND um.mission_id = m.id
		WHERE m.id IN (:missionIds)
		GROUP BY m.id, m.target_count
		ORDER BY m.id
		""", nativeQuery = true)
	List<MissionProgressProjection> findProgress(
		@Param("userId") long userId,
		@Param("missionIds") Collection<Long> missionIds
	);

	/**
	 * 후보 역조회 (MSG-223 §D2 1단계, idx_mission_grids_grid). 업로드 격자를 대상으로 하는 활성 미션 중
	 * 아직 스탬프를 안 받은 것만 — 빈 결과(대부분의 업로드)면 판정 쿼리 없이 조기 종료한다(FR-18).
	 * 활성 술어는 findActive 와 동형이되 statement_timestamp() 인 이유: 업로드 트랜잭션이 S3 복사를 끼고
	 * 길어질 수 있어 now() 의 트랜잭션 시작 시각 고정을 피한다(MSG-200 선례). AT TIME ZONE 'UTC' 는
	 * 저장값이 UTC 순간(§D3)이라서다 — 세션 타임존(JVM 기본, 로컬 KST)으로 비교하면 9시간 스큐가 난다.
	 */
	@Query(value = """
		SELECT m.id
		FROM mission_grids mg
		JOIN missions m ON m.id = mg.mission_id
		WHERE mg.grid_id = :gridId
		  AND m.hidden_at IS NULL
		  AND (m.start_at IS NULL OR m.start_at <= statement_timestamp() AT TIME ZONE 'UTC')
		  AND (m.end_at IS NULL OR statement_timestamp() AT TIME ZONE 'UTC' <= m.end_at)
		  AND NOT EXISTS (SELECT 1 FROM user_missions um
		                  WHERE um.user_id = :userId AND um.mission_id = m.id)
		""", nativeQuery = true)
	List<Long> findAwardCandidateIds(@Param("gridId") String gridId, @Param("userId") long userId);

	/**
	 * 단일 판정 쿼리 (MSG-223 §D2 2단계 — 유형 5종 무분기, FR-13). 후보 미션 전부를 한 번에:
	 * 대상 격자 중 서로 다른 격자 target_count 곳 이상에 기간 내 recorded_at 방문 영상이 있으면 충족.
	 * 기간 조건이 IS NULL OR 형태라 무기간(코스)은 자연 생략된다(FR-12) — 코드 분기 없음. 경계는 양끝
	 * 포함(findActive 동형). videos 조인은 v.user_id 를 ON 절에 태워 idx_videos_user_created prefix 로
	 * 내 영상만 훑는다. status <> 'DELETED' 만 거른다 — BLINDED 는 촬영 사실이 유효하므로 포함(§D2).
	 * 행사 영상(event_videos 행이 붙은 영상)은 NOT EXISTS 로 뺀다 — 현장에 없어도 올릴 수 있어 격자 방문
	 * 증거가 아니다(MSG-450). 이 줄도 findProgress 와의 글자 단위 동일 계약에 포함된다.
	 * ORDER BY m.id 는 응답 배열 순서의 원천이다 (MSG-363 §D3) — 집계 결과 순서는 PostgreSQL 이
	 * 보장하지 않아, 한 업로드가 두 종류를 완료하면 completedMissions·newBadges 순서가 실행마다 흔들린다.
	 */
	@Query(value = """
		SELECT m.id AS "missionId", m.title, m.type
		FROM missions m
		JOIN mission_grids mg ON mg.mission_id = m.id
		JOIN videos v ON v.grid_id = mg.grid_id
			AND v.user_id = :userId
			AND v.status <> 'DELETED'
			AND NOT EXISTS (SELECT 1 FROM event_videos ev WHERE ev.video_id = v.id)
			AND (m.start_at IS NULL OR v.recorded_at >= m.start_at)
			AND (m.end_at IS NULL OR v.recorded_at <= m.end_at)
		WHERE m.id IN (:candidateIds)
		  AND m.hidden_at IS NULL
		GROUP BY m.id, m.title, m.type, m.target_count
		HAVING COUNT(DISTINCT v.grid_id) >= m.target_count
		ORDER BY m.id
		""", nativeQuery = true)
	List<CompletedMissionProjection> findCompleted(
		@Param("userId") long userId,
		@Param("candidateIds") Collection<Long> candidateIds
	);

	/**
	 * 격자 역조회 (MSG-459 FR-15) — 그 격자를 <b>대표 격자로 갖는</b> 미션. 역방향 인덱스
	 * idx_mission_grids_grid 를 쓰지 않는 것이 계약이다: 그 인덱스는 "이 격자를 판정 범위로 갖는 미션"을
	 * 찾는 물건이라 축제 하나가 81칸 전부에 걸린다. 격자를 눌렀을 때 나와야 하는 것은 판정 범위에 걸린
	 * 미션이 아니라 영상이 모인 자리로 지목된 미션이므로 조인 없이 컬럼 하나로 찾는다(D-4).
	 * 접근 경로는 부분 인덱스 idx_missions_representative_grid 다.
	 * <p>
	 * 기간 조건이 없다 — 끝난 축제도 나와야 그 격자를 눌러 무슨 축제였는지 되짚을 수 있다(FR-15).
	 * 어느 미션의 대표 격자도 아닌 값과 격자 포맷이 아닌 문자열은 빈 목록이다(예외 아님).
	 * 노출 중지된 미션은 빠진다 (MSG-500 D-3) — 목록에서 사라진 미션이 격자 선택으로는 계속 보이면 안 된다.
	 * 표시 정렬(진행 중 → 시작 전 → 종료)은 서비스가 잡는다 — 서버 시각과 견주는 순위라 SQL 에 굳히지
	 * 않는다. id 정렬은 그 앞의 결정성 확보다(findBySource 와 같은 이유).
	 */
	List<Mission> findByRepresentativeGridIdAndHiddenAtIsNullOrderById(String representativeGridId);

	/**
	 * 시더 dedupe DB 대조용 (MSG-224 D3·D7) — 해당 러너 산출물만 기간과 함께 로드해 메모리 대조한다.
	 * type 조회 금지: EVENT 는 팝업(MSG-235, 1격자)과 공유 타입이라 1격자 미션의 min+4 가 가짜 중심 키를
	 * 만들어 실축제를 오스킵한다(Codex 리뷰 파생).
	 *
	 * ORDER BY m.id 는 시더 3종의 메타데이터 백필이 기대는 계약이다 (MSG-383 D6, Codex 리뷰 파생) —
	 * 같은 dedupe 키를 가진 행이 둘 이상이면 셋 다 "먼저 읽은 하나"만 갱신하는데, 정렬이 없으면 그
	 * 하나가 실행 계획에 따라 달라져 실행마다 다른 행이 갱신된다.
	 */
	@Query("SELECT m FROM Mission m WHERE m.source = :source ORDER BY m.id")
	List<Mission> findBySource(@Param("source") String source);

	/**
	 * 종료 미션 정리 — 러너별 :source 한정 (MSG-224 D4 → MSG-235 D4 파라미터화, 동일 SQL 2벌 복제 금지).
	 * 타 소스·NULL(수동/미상)은 불가침(FR-8). mission_grids 는 ON DELETE CASCADE 로 함께 정리된다.
	 * AT TIME ZONE 'UTC' 필수 — 저장값이 UTC 순간이라 세션 타임존(KST) 캐스트 비교는 9시간 스큐가 난다
	 * (MSG-223 §D2 규칙, findAwardCandidateIds 동일 패턴). 스탬프 걸린 미션은 NOT EXISTS 로 건너뛴다 —
	 * V6 FK(user_missions.mission_id NO ACTION)가 하드삭제를 차단하므로 시도 자체가 러너 전체를 롤백시킨다.
	 * 당초 type='EVENT' 한정은 공유 타입이라 종료 팝업을 오삭제하는 결함이었다(Codex 리뷰 파생, 2026-07-31
	 * 성민 확정). flush: 같은 트랜잭션에서 방금 save 된 미션도 정리 대상 판정에 들어가야 하고(러너 단일
	 * 트랜잭션, D4), clear: native DELETE 는 영속성 컨텍스트를 우회하므로 삭제된 엔티티가 1차 캐시에
	 * 스테일로 남는 것을 막는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
		DELETE FROM missions m
		WHERE m.source = :source
		  AND m.end_at IS NOT NULL
		  AND m.end_at < statement_timestamp() AT TIME ZONE 'UTC'
		  AND NOT EXISTS (SELECT 1 FROM user_missions um WHERE um.mission_id = m.id)
		""", nativeQuery = true)
	int deleteEndedBySourceWithoutStamps(@Param("source") String source);

	/**
	 * 노출 중지 (MSG-500 D-3) — 관리자가 승인 행사를 내릴 때 그 산출물 미션을 숨긴다. 삭제가 아닌 이유는
	 * user_missions FK 가 스탬프 걸린 미션의 하드삭제를 막고, 이미 준 스탬프를 뺏는 것이 비회수 원칙과
	 * 충돌하기 때문이다.
	 * <p>
	 * 엔티티를 로드해 더티 체킹으로 바꾸지 않고 <b>단일 컬럼 UPDATE</b> 인 것이 계약이다 — 이 프로젝트는
	 * {@code @DynamicUpdate} 를 쓰지 않아 관리 엔티티의 flush 가 전 컬럼 UPDATE 를 내보내고, 그러면 중지가
	 * 같은 시각의 다른 갱신(시더 메타데이터 백필·대표 격자 재산출)을 낡은 스냅숏으로 되덮는다.
	 * {@code hidden_at IS NULL} 술어는 재중지를 0행으로 만들어 첫 중지 시각을 보존한다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("UPDATE Mission m SET m.hiddenAt = :now WHERE m.id = :missionId AND m.hiddenAt IS NULL")
	int hide(@Param("missionId") long missionId, @Param("now") LocalDateTime now);
}

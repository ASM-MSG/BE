package com.msg.fillmap.event.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventLocationGrid;
import com.msg.fillmap.event.entity.EventLocationGridId;

public interface EventLocationGridRepository extends JpaRepository<EventLocationGrid, EventLocationGridId> {

	List<EventLocationGrid> findByIdEventLocationId(Long eventLocationId);

	/**
	 * 위치 여러 개의 영역 격자를 한 번에 (MSG-439 API 3). 위치마다 따로 부르면 목록 길이만큼 쿼리가 늘어난다.
	 * 정렬은 격자 문자열 오름차순 — gridIds 배열이 요청마다 같은 순서로 나가 응답 비교가 결정적이다.
	 */
	List<EventLocationGrid> findByIdEventLocationIdInOrderByIdGridIdAsc(Collection<Long> eventLocationIds);

	/**
	 * 격자 역조회 (MSG-439 API 4) — 격자 행이 아니라 그 격자가 속한 위치를 돌려준다. 응답이 회차의 제목·기간·
	 * 노출 여부를 전부 쓰므로 회차를 fetch join 으로 함께 읽는다(없으면 행마다 지연 로딩이 붙는다).
	 * 어느 위치에도 안 든 격자와 격자 포맷이 아닌 문자열은 빈 목록이다 — 일치 조회라 포맷 검증이 판정을
	 * 바꾸지 못하고, 빈 목록이면 호출자가 표시명 판정에 들어가기 전에 끝난다.
	 */
	@Query("""
		SELECT l FROM EventLocationGrid g
		JOIN g.location l
		JOIN FETCH l.occurrence
		WHERE g.id.gridId = :gridId
		""")
	List<EventLocation> findLocationsByGridId(@Param("gridId") String gridId);

	/**
	 * 회차의 전 격자 (MSG-500 D-9 겹침 사전 검사) — <b>숨긴 위치의 격자도 포함한다.</b>
	 * uq_event_grid_per_occ 는 행이 있으면 걸리는 제약이라 숨김 여부를 보지 않기 때문이다. 가시 격자만
	 * 세면 중지된 위치와 같은 칸을 새 승인이 집어 커밋 시점 500 이 된다.
	 */
	@Query("SELECT g.id.gridId FROM EventLocationGrid g WHERE g.eventOccurrenceId = :occurrenceId")
	List<String> findGridIdsByOccurrenceId(@Param("occurrenceId") Long occurrenceId);

	/**
	 * 회차의 <b>가시</b> 격자 (MSG-500 D-3 노출 영역 재계산) — 위 조회와 정확히 반대 용도다. 노출 영역은
	 * "보이는 위치들을 감싸는 범위"라는 불변식이라 숨긴 위치의 칸이 들어가면 중지가 영역을 줄이지 못한다.
	 */
	@Query("""
		SELECT g.id.gridId FROM EventLocationGrid g
		JOIN g.location l
		WHERE g.eventOccurrenceId = :occurrenceId AND l.hiddenAt IS NULL
		""")
	List<String> findVisibleGridIdsByOccurrenceId(@Param("occurrenceId") Long occurrenceId);

	/**
	 * 회차의 가시 격자 중 <b>승인 산출물 위치(locationKey 접두 {@code sub-})의 것만</b> (MSG-500 재시드 보존).
	 * 위 조회와 갈라 둔 것은 시더가 <b>순서에 무관</b>해야 하기 때문이다 — 시더는 회차를 먼저 갱신하고 위치를
	 * 나중에 동기화하므로, 전 가시 격자를 합집합에 넣으면 시드가 줄이거나 옮긴 <b>옛 시드 격자</b>가 아직 살아
	 * 있는 채로 들어가 부풀린 영역이 다음 재기동까지 남는다. 시드 위치의 기여는 시드 사각형이 정본이므로
	 * 확장 입력에서 빼는 것이 의미상으로도 맞다.
	 */
	@Query("""
		SELECT g.id.gridId FROM EventLocationGrid g
		JOIN g.location l
		WHERE g.eventOccurrenceId = :occurrenceId AND l.hiddenAt IS NULL
			AND l.locationKey LIKE CONCAT(:locationKeyPrefix, '%')
		""")
	List<String> findVisibleGridIdsByOccurrenceIdAndKeyPrefix(@Param("occurrenceId") Long occurrenceId,
		@Param("locationKeyPrefix") String locationKeyPrefix);
}

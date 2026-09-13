package com.msg.fillmap.event.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
	 * 회차의 전 격자 (MSG-500 D-9 겹침 사전 검사) — 숨김 여부를 보지 않는 것이 의도다.
	 * uq_event_grid_per_occ 는 행이 있으면 걸리는 제약이므로 사전 검사가 <b>제약과 같은 집합</b>을 봐야
	 * 커밋 시점 500 대신 읽을 수 있는 13452 가 나간다. 중지된 위치의 행은 클레임 반납으로 애초에
	 * 사라져 있어(MSG-598) 이 조회에 걸리지 않는다 — 술어로 거르지 않는 이유가 그것이다.
	 */
	@Query("SELECT g.id.gridId FROM EventLocationGrid g WHERE g.eventOccurrenceId = :occurrenceId")
	List<String> findGridIdsByOccurrenceId(@Param("occurrenceId") Long occurrenceId);

	/**
	 * 격자 클레임 반납 (MSG-598) — 접두로 잡히는 위치의 칸 행을 지워 같은 회차의 새 승인이 그 자리를
	 * 다시 쓸 수 있게 한다. {@code hidden_at IS NOT NULL} 을 함께 거는 것은 노출 중인 위치의 영역을
	 * 실수로 날리지 않기 위한 안전장치다 — 호출 시점엔 접두의 전 위치가 이미 숨겨져 있으므로 판정을
	 * 바꾸지 않고, 호출 순서가 어긋나는 날 손실을 막는다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
		DELETE FROM EventLocationGrid g
		WHERE g.id.eventLocationId IN (
			SELECT l.id FROM EventLocation l
			WHERE l.locationKey LIKE CONCAT(:locationKeyPrefix, '%') AND l.hiddenAt IS NOT NULL)
		""")
	int deleteByHiddenLocationKeyPrefix(@Param("locationKeyPrefix") String locationKeyPrefix);

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

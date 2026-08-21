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
}

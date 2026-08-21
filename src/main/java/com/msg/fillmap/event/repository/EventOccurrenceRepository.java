package com.msg.fillmap.event.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.event.entity.EventOccurrence;

public interface EventOccurrenceRepository extends JpaRepository<EventOccurrence, Long> {

	Optional<EventOccurrence> findByOccurrenceKey(String occurrenceKey);

	/**
	 * 칩 후보 — 노출이 시작된 회차 전량 (MSG-439 API 1). 상태(예정·진행 중)와 뷰포트 겹침은 자바가 거른다:
	 * 상태 판정은 {@link EventOccurrence#statusAt} 하나를 네 조회가 공유해야 하고, 행사 행이 도시당 한둘뿐이라
	 * 공간 쿼리나 인덱스를 만들 이유가 없다. 미노출 예정 회차는 이 술어에서 이미 빠져 존재 은닉이 성립한다.
	 */
	List<EventOccurrence> findByVisibleFromLessThanEqual(LocalDateTime now);

	/**
	 * 상세용 단건 — 시리즈를 함께 읽는다 (MSG-439 API 2). 응답의 seriesId 와 이전 회차 조회 키가 시리즈라
	 * fetch join 으로 지연 로딩 한 번을 없앤다.
	 */
	@Query("SELECT o FROM EventOccurrence o JOIN FETCH o.series WHERE o.id = :occurrenceId")
	Optional<EventOccurrence> findWithSeriesById(@Param("occurrenceId") Long occurrenceId);

	/**
	 * 같은 시리즈의 지난 회차 (MSG-439 API 2). 술어는 "같은 시리즈 AND 시작이 기준 회차보다 앞"이고,
	 * 정렬은 최신순 → id 내림차순 타이브레이커라 결정적이다. 노출 판정은 호출자가 자바에서 한 번 더 건다.
	 */
	List<EventOccurrence> findBySeriesIdAndStartsAtLessThanOrderByStartsAtDescIdDesc(
		Long seriesId, LocalDateTime startsAt);
}

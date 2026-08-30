package com.msg.fillmap.event.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
	 * 행사 운영자 콘솔의 승인 이벤트 후보 — 아직 끝나지 않은 회차 전량 (MSG-501). 종료 정각은 이미 종료라
	 * 빠진다(파생 상태로 UPCOMING·LIVE 만 남는다). 시·도 필터·이름 검색·집계·정렬은 후보가 수십 건 규모라
	 * 자바가 한다 — 전체 기준 집계와 필터 적용 목록이 이 한 번의 조회에서 함께 나온다.
	 * <p>
	 * 유저 조회의 노출 판정(visibleFrom)은 걸지 않는다 (결정 D-2) — 행사 운영자는 노출 시작 전에 미리 부모
	 * 이벤트를 골라 참여를 신청할 수 있어야 심사 시간이 행사 시작 안에 든다.
	 */
	List<EventOccurrence> findByEndsAtAfter(LocalDateTime now);

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

	/**
	 * 시작 알림 발송 후보 (MSG-442) — 이미 시작했고, 아직 끝나지 않았으며, 시작한 지 발송 창(24시간) 안인
	 * 회차다. 세 번째 조건은 dedupe 가 아니라 스캔 상한이다: 열흘짜리 LIVE 회차를 매 틱 다시 훑어 전 구독자
	 * 무효 insert 를 반복하지 않게 한다. {@code now < startsAt + 24시간} 을 바인딩 가능한 형태로 옮긴 것이
	 * {@code startsAt > windowFloor} 다 (windowFloor = now - 24시간).
	 */
	@Query("""
		SELECT o FROM EventOccurrence o
		WHERE o.startsAt <= :now AND o.endsAt > :now AND o.startsAt > :windowFloor
		""")
	List<EventOccurrence> findStartNotificationCandidates(
		@Param("now") LocalDateTime now, @Param("windowFloor") LocalDateTime windowFloor);

	/**
	 * 승인·중지의 회차 직렬화 잠금 (MSG-500 D-9) — 참여형 승인이 격자 겹침을 검사하고 삽입하는 사이에
	 * 다른 승인이 끼어드는 경합을 트랜잭션 순서로 막는다. 잠금이 없으면 두 사전 검사가 모두 통과하고,
	 * uq_event_grid_per_occ 가 <b>지연 제약</b>이라 지는 쪽이 커밋 시점 500 으로 끝난다(관리자에게 읽을 수
	 * 없는 오류다). 중지도 같은 잠금을 <b>같은 순서로</b> 잡는다 — 승인과 중지가 노출 영역을 함께 고쳐서다.
	 * {@code EventLocationRepository.findWithLockByLocationKey} 와 같은 패턴이다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<EventOccurrence> findWithLockById(Long id);
}

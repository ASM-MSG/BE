package com.msg.fillmap.event.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.event.entity.EventVideo;

public interface EventVideoRepository extends JpaRepository<EventVideo, Long> {

	/** 대표 격자 변경 드리프트 가드 — 영상이 하나라도 붙은 위치는 대표 격자를 바꿀 수 없다 (MSG-438). */
	boolean existsByLocationId(Long eventLocationId);

	/**
	 * 위치 목록의 영상 수 (MSG-439 API 3) — 회차의 위치 전부를 group by 한 번으로 센다. 위치마다 count 를
	 * 부르면 N+1 이고, 조회 키가 그 회차의 위치 id 집합이라 다른 회차 영상이 섞일 수 없다 (FR-EVENT-07).
	 * 노출 게이트 3종(ACTIVE·PUBLIC·READY)은 MSG-440 피드와 <b>동등</b>하다 — 같은 정의를 써야 삭제·비공개
	 * 영상의 존재가 숫자로 새지 않는다. 차단 절(MSG-569)은 요청자를 아는 피드에만 있고 이 카운트에는 없다 —
	 * "노출 게이트 3종은 동등, 차단 절은 요청자를 아는 목록에만"이라 차단한 사용자에게만 카드 숫자가 목록 행 수보다
	 * 클 수 있고 그 차이를 허용한다(MSG-390 이 BLINDED 에서 허용한 것과 같은 취급).
	 * event_videos 는 videos 를 1:1 로 확장하므로(video_id 가 PK) 조인 팬아웃이 없어 DISTINCT 가 필요 없다.
	 */
	@Query("""
		SELECT new com.msg.fillmap.event.repository.EventLocationVideoCount(ev.location.id, COUNT(ev))
		FROM EventVideo ev
		JOIN ev.video v
		WHERE ev.location.id IN :locationIds
		  AND v.status = com.msg.fillmap.video.entity.VideoStatus.ACTIVE
		  AND v.visibility = com.msg.fillmap.video.entity.Visibility.PUBLIC
		  AND v.processingStatus = com.msg.fillmap.video.entity.ProcessingStatus.READY
		GROUP BY ev.location.id
		""")
	List<EventLocationVideoCount> countVisibleByLocationIds(@Param("locationIds") Collection<Long> locationIds);

	/**
	 * 위치별 영상 피드 첫 페이지 (MSG-440 API 2). <b>노출 게이트 3종은 바로 위 카운트와 동등 계약</b>이라 두 쿼리를
	 * 붙여 둔다 — 표현이 갈라지면 위치 목록의 영상 수와 이 피드가 어긋나고, 그 차이가 곧 삭제·비공개 영상의
	 * 존재 노출이다. 차단 절(MSG-569 D-4)은 viewerId(비로그인 null)가 있을 때만 어느 방향이든 차단 관계인
	 * 작성자의 영상을 NOT EXISTS 안티 조인으로 뺀다 — 쿼리 안에서 걸러야 빠진 행이 페이지 크기를 소비하지 않는다.
	 * 프로젝션의 v.userId 는 응답의 uploaderId(차단 대상 식별자)다. 정렬은 업로드 시각 내림차순 + id 내림차순 타이브레이커라 같은 시각 업로드가 둘이어도
	 * 페이지 경계가 결정적이다. 건수 제한은 Pageable 이 지고(JPQL 에 LIMIT 이 없다) 서비스가 size+1 lookahead
	 * 로 넘긴다 — Pageable 에 Sort 를 실으면 ORDER BY 계약이 흔들리므로 정렬 없는 PageRequest 여야 한다.
	 */
	@Query("""
		SELECT new com.msg.fillmap.event.repository.EventLocationVideoRow(
			v.id, v.thumbnailUrl, v.durationSec, v.createdAt, v.userId)
		FROM EventVideo ev
		JOIN ev.video v
		WHERE ev.location.id = :locationId
		  AND v.status = com.msg.fillmap.video.entity.VideoStatus.ACTIVE
		  AND v.visibility = com.msg.fillmap.video.entity.Visibility.PUBLIC
		  AND v.processingStatus = com.msg.fillmap.video.entity.ProcessingStatus.READY
		  AND (:viewerId IS NULL OR NOT EXISTS (
		    SELECT 1 FROM UserBlock b
		    WHERE (b.id.blockerId = :viewerId AND b.id.blockedId = v.userId)
		       OR (b.id.blockerId = v.userId AND b.id.blockedId = :viewerId)))
		ORDER BY v.createdAt DESC, v.id DESC
		""")
	List<EventLocationVideoRow> findVisibleByLocationId(@Param("locationId") Long locationId,
		@Param("viewerId") Long viewerId, Pageable pageable);

	/**
	 * 위치별 영상 피드 다음 페이지 (MSG-440 API 2) — 커서 경계값 뒤부터 같은 술어·같은 정렬로 이어 읽는다.
	 * keyset 조건은 정렬 키와 같은 (createdAt, id) 쌍이라 항목이 끼어들어도 중복·누락 없이 이어진다.
	 */
	@Query("""
		SELECT new com.msg.fillmap.event.repository.EventLocationVideoRow(
			v.id, v.thumbnailUrl, v.durationSec, v.createdAt, v.userId)
		FROM EventVideo ev
		JOIN ev.video v
		WHERE ev.location.id = :locationId
		  AND v.status = com.msg.fillmap.video.entity.VideoStatus.ACTIVE
		  AND v.visibility = com.msg.fillmap.video.entity.Visibility.PUBLIC
		  AND v.processingStatus = com.msg.fillmap.video.entity.ProcessingStatus.READY
		  AND (:viewerId IS NULL OR NOT EXISTS (
		    SELECT 1 FROM UserBlock b
		    WHERE (b.id.blockerId = :viewerId AND b.id.blockedId = v.userId)
		       OR (b.id.blockerId = v.userId AND b.id.blockedId = :viewerId)))
		  AND (v.createdAt < :cursorCreatedAt OR (v.createdAt = :cursorCreatedAt AND v.id < :cursorId))
		ORDER BY v.createdAt DESC, v.id DESC
		""")
	List<EventLocationVideoRow> findVisibleByLocationIdAfter(
		@Param("locationId") Long locationId,
		@Param("viewerId") Long viewerId,
		@Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
		@Param("cursorId") Long cursorId,
		Pageable pageable);

	/**
	 * 반응 경로(MSG-441)가 쓰는 단건 로딩 — 노출 술어는 <b>바로 위 두 쿼리와 동등 계약</b>이라 셋을 붙여 둔다.
	 * 댓글·도움돼요 변경 다섯과 댓글 목록이 전부 이 하나로 대상을 연다 — 판정을 서비스마다 손으로 반복하면
	 * 표현이 갈라지고, 갈라지는 순간 한쪽 경로로만 삭제·비공개 영상의 존재가 새어 나간다.
	 * JOIN FETCH 로 위치와 회차를 함께 읽는 것은 호출자가 다음 줄에서 바로 잠금 가드에 회차를 넘기기
	 * 때문이다(지연 로딩이면 왕복이 하나 더 생긴다). 영상도 FETCH 인 이유는 같은 서두의 차단 판정(MSG-569 D-7)이
	 * 작성자 id 를 읽기 때문이다 — 같은 문장의 조인이라 계획은 그대로고 지연 로딩 1회가 사라진다.
	 * 회차의 노출 은닉 판정은 이 쿼리가 아니라
	 * {@link com.msg.fillmap.event.entity.EventOccurrence#isVisibleAt} 이 한다 — MSG-442 가 조회 네 경로와
	 * 공유하려고 엔티티에 올려 둔 술어다.
	 */
	@Query("""
		SELECT ev FROM EventVideo ev
		JOIN FETCH ev.location l
		JOIN FETCH l.occurrence
		JOIN FETCH ev.video v
		WHERE ev.videoId = :videoId
		  AND v.status = com.msg.fillmap.video.entity.VideoStatus.ACTIVE
		  AND v.visibility = com.msg.fillmap.video.entity.Visibility.PUBLIC
		  AND v.processingStatus = com.msg.fillmap.video.entity.ProcessingStatus.READY
		""")
	Optional<EventVideo> findVisibleWithOccurrence(@Param("videoId") Long videoId);
}

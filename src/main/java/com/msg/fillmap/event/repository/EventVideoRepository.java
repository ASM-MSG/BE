package com.msg.fillmap.event.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

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
	 * 노출 술어는 전역 게이트(ACTIVE·PUBLIC·READY)와 <b>동등</b>하다 — 카운트와 MSG-440 피드가 같은 정의를
	 * 써야 "숫자는 있는데 목록엔 없는" 불일치와 삭제·비공개 영상의 존재 노출이 안 생긴다.
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
	 * 위치별 영상 피드 첫 페이지 (MSG-440 API 2). <b>노출 술어는 바로 위 카운트와 동등 계약</b>이라 두 쿼리를
	 * 붙여 둔다 — 표현이 갈라지면 위치 목록의 영상 수와 이 피드가 어긋나고, 그 차이가 곧 삭제·비공개 영상의
	 * 존재 노출이다. 정렬은 업로드 시각 내림차순 + id 내림차순 타이브레이커라 같은 시각 업로드가 둘이어도
	 * 페이지 경계가 결정적이다. 건수 제한은 Pageable 이 지고(JPQL 에 LIMIT 이 없다) 서비스가 size+1 lookahead
	 * 로 넘긴다 — Pageable 에 Sort 를 실으면 ORDER BY 계약이 흔들리므로 정렬 없는 PageRequest 여야 한다.
	 */
	@Query("""
		SELECT new com.msg.fillmap.event.repository.EventLocationVideoRow(
			v.id, v.thumbnailUrl, v.durationSec, v.createdAt)
		FROM EventVideo ev
		JOIN ev.video v
		WHERE ev.location.id = :locationId
		  AND v.status = com.msg.fillmap.video.entity.VideoStatus.ACTIVE
		  AND v.visibility = com.msg.fillmap.video.entity.Visibility.PUBLIC
		  AND v.processingStatus = com.msg.fillmap.video.entity.ProcessingStatus.READY
		ORDER BY v.createdAt DESC, v.id DESC
		""")
	List<EventLocationVideoRow> findVisibleByLocationId(@Param("locationId") Long locationId, Pageable pageable);

	/**
	 * 위치별 영상 피드 다음 페이지 (MSG-440 API 2) — 커서 경계값 뒤부터 같은 술어·같은 정렬로 이어 읽는다.
	 * keyset 조건은 정렬 키와 같은 (createdAt, id) 쌍이라 항목이 끼어들어도 중복·누락 없이 이어진다.
	 */
	@Query("""
		SELECT new com.msg.fillmap.event.repository.EventLocationVideoRow(
			v.id, v.thumbnailUrl, v.durationSec, v.createdAt)
		FROM EventVideo ev
		JOIN ev.video v
		WHERE ev.location.id = :locationId
		  AND v.status = com.msg.fillmap.video.entity.VideoStatus.ACTIVE
		  AND v.visibility = com.msg.fillmap.video.entity.Visibility.PUBLIC
		  AND v.processingStatus = com.msg.fillmap.video.entity.ProcessingStatus.READY
		  AND (v.createdAt < :cursorCreatedAt OR (v.createdAt = :cursorCreatedAt AND v.id < :cursorId))
		ORDER BY v.createdAt DESC, v.id DESC
		""")
	List<EventLocationVideoRow> findVisibleByLocationIdAfter(
		@Param("locationId") Long locationId,
		@Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
		@Param("cursorId") Long cursorId,
		Pageable pageable);
}

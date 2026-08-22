package com.msg.fillmap.event.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.event.entity.EventVideoComment;

/**
 * 행사 영상 댓글 리포지토리 (MSG-441). 정렬은 {@code id ASC} 하나다 — BIGSERIAL 단조 증가라 작성 순서와
 * 같은 축이고 유일 키라 타이브레이커가 필요 없다(피드가 (createdAt, id) 두 키를 쓴 것은 서로 다른 영상의
 * created_at 이 같을 수 있어서다).
 * <p>
 * 파생 쿼리 {@code countByVideo_VideoId} 의 밑줄은 프로퍼티 경로 표기다 — {@link com.msg.fillmap.event.entity.EventVideo}
 * 의 식별자 필드가 관례와 달리 {@code videoId} 라, 밑줄로 경계를 명시하지 않으면 스프링 데이터가 프로퍼티를
 * 풀지 못해 애플리케이션 기동 시 리포지토리 초기화가 실패한다.
 */
public interface EventVideoCommentRepository extends JpaRepository<EventVideoComment, Long> {

	/**
	 * 댓글 목록 첫 페이지 (오래된 순). 작성자 닉네임은 세타 조인 + 생성자 프로젝션으로 함께 읽는다 —
	 * userId 를 연관으로 매핑하지 않은 도메인 결정의 귀결이고, 컨벤션이 정한 형태다(Report 선례).
	 * 건수 제한은 Pageable 이 지고(JPQL 에 LIMIT 이 없다) 서비스가 size+1 lookahead 로 넘긴다 —
	 * Pageable 에 Sort 를 실으면 ORDER BY 계약이 흔들리므로 정렬 없는 PageRequest 여야 한다.
	 */
	@Query("""
		SELECT new com.msg.fillmap.event.repository.EventVideoCommentRow(
			c.id, c.userId, u.nickname, c.content, c.createdAt)
		FROM EventVideoComment c, User u
		WHERE u.id = c.userId
		  AND c.video.videoId = :videoId
		ORDER BY c.id ASC
		""")
	List<EventVideoCommentRow> findPageByVideoId(@Param("videoId") Long videoId, Pageable pageable);

	/** 다음 페이지 — 위와 같은 문장에 keyset 경계 하나를 더한 형태다(정렬 키가 유일해 조건도 하나다). */
	@Query("""
		SELECT new com.msg.fillmap.event.repository.EventVideoCommentRow(
			c.id, c.userId, u.nickname, c.content, c.createdAt)
		FROM EventVideoComment c, User u
		WHERE u.id = c.userId
		  AND c.video.videoId = :videoId
		  AND c.id > :cursorId
		ORDER BY c.id ASC
		""")
	List<EventVideoCommentRow> findPageByVideoIdAfter(@Param("videoId") Long videoId,
		@Param("cursorId") Long cursorId, Pageable pageable);

	/** 상세 단건의 댓글 수. */
	long countByVideo_VideoId(Long videoId);

	/**
	 * 피드 한 페이지의 댓글 수 배치 집계 (최대 50 영상). 항목마다 세면 N+1 이라 group by 한 번으로 돈다 —
	 * countVisibleByLocationIds 와 같은 형태다. 영상 상태 필터가 없는 이유는 노출 술어가 이미 앞단에서
	 * 그 영상을 걸러 내기 때문이다(MSG-441 §집계 조회).
	 */
	@Query("""
		SELECT new com.msg.fillmap.event.repository.EventVideoReactionCount(c.video.videoId, COUNT(c))
		FROM EventVideoComment c
		WHERE c.video.videoId IN :videoIds
		GROUP BY c.video.videoId
		""")
	List<EventVideoReactionCount> countCommentsByVideoIds(@Param("videoIds") Collection<Long> videoIds);
}

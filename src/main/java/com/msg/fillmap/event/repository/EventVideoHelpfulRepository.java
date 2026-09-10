package com.msg.fillmap.event.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.event.entity.EventVideoHelpful;
import com.msg.fillmap.event.entity.EventVideoHelpfulId;

/**
 * 행사 영상 도움돼요 리포지토리 (MSG-441). 쓰기는 native 2종(INSERT ON CONFLICT · DELETE)뿐이고 둘 다
 * 1문장 멱등이라 같은 요청 재전송이 오류 없이 같은 상태로 끝난다 (EventNotificationSubscriptionRepository ·
 * NotificationOptOutRepository 선례). {@code deleteById} 를 쓰지 않는 이유는 그것이 SELECT 후 DELETE 두
 * 문장이라, 같은 사용자의 동시 취소 두 건에서 진 쪽의 DELETE 가 0행이 되어 StaleStateException(공통 500)
 * 이 되기 때문이다 — 추가가 ON CONFLICT 로 이미 그 창을 닫아 두었으므로 취소만 열어 두면 대칭이 깨진다.
 * <p>
 * 내가 눌렀는지 판정은 기본 제공 existsById (PK 단건 히트). 파생 쿼리 {@code countById_VideoId} 의 밑줄은
 * 프로퍼티 경로 표기다 — 값이 {@code @EmbeddedId id} 안에 있어 경로가 {@code id.videoId} 이고, 밑줄이
 * 없으면 기동 시 리포지토리 초기화가 실패한다.
 */
public interface EventVideoHelpfulRepository extends JpaRepository<EventVideoHelpful, EventVideoHelpfulId> {

	/** 도움돼요 추가. 이미 누른 상태면 DO NOTHING 0행 = 멱등, created_at 도 첫 누름 시각 그대로 남는다. */
	@Modifying
	@Query(value = """
		INSERT INTO event_video_helpfuls (video_id, user_id)
		VALUES (:videoId, :userId)
		ON CONFLICT (video_id, user_id) DO NOTHING
		""", nativeQuery = true)
	int insertHelpful(@Param("videoId") long videoId, @Param("userId") long userId);

	/** 도움돼요 취소 — 행 삭제. 누른 적 없어도 0행 = 멱등. */
	@Modifying
	@Query(value = """
		DELETE FROM event_video_helpfuls
		WHERE video_id = :videoId AND user_id = :userId
		""", nativeQuery = true)
	int deleteHelpful(@Param("videoId") long videoId, @Param("userId") long userId);

	/** 상세 단건의 도움돼요 수. */
	long countById_VideoId(Long videoId);

	/** 피드 한 페이지의 도움돼요 수 배치 집계 — 댓글 쪽과 같은 이유·같은 형태다. */
	@Query("""
		SELECT new com.msg.fillmap.event.repository.EventVideoReactionCount(h.id.videoId, COUNT(h))
		FROM EventVideoHelpful h
		WHERE h.id.videoId IN :videoIds
		GROUP BY h.id.videoId
		""")
	List<EventVideoReactionCount> countHelpfulsByVideoIds(@Param("videoIds") Collection<Long> videoIds);
}

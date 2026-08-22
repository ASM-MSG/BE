package com.msg.fillmap.event.service;

import java.util.Collection;

import com.msg.fillmap.event.dto.EventVideoCommentPageResponseDto;
import com.msg.fillmap.event.dto.EventVideoCommentResponseDto;
import com.msg.fillmap.event.dto.EventVideoHelpfulResponseDto;

/**
 * 행사 영상의 댓글과 도움돼요 (MSG-441). event 도메인 내부 계약이라 다른 도메인이 소비하지 않는다.
 * 모든 경로가 공유하는 규칙 둘:
 * ① 대상 영상은 MSG-440 상세와 <b>같은 술어</b>로만 열린다 — 행사 영상 연결이 있고 ACTIVE·PUBLIC·READY
 * 이며 소속 회차가 노출 상태여야 하고, 하나라도 어긋나면 소유자 본인에게도 13406 이다.
 * ② 변경 경로 다섯은 영상을 연 직후 잠금 가드를 부르고 조회 경로는 부르지 않는다 — 아카이브된 행사에서
 * 대화는 닫히지만 기록은 계속 보여야 한다(FR-14). 잠금 시점은 종료가 아니라 아카이브 전환(종료 + 30일)
 * 이라 유예 기간에는 반응이 열려 있다 (2026-08-21 번복).
 */
public interface EventVideoInteractionService {

	/** 댓글 작성 (API 1). 아카이브된 행사(종료 + 30일 이후)면 13422, 노출 술어 밖이면 13406 이다. */
	EventVideoCommentResponseDto createComment(long userId, long videoId, String content);

	/**
	 * 댓글 수정 (API 2). 본인만 성공한다 — 남의 댓글은 13403, 없거나 다른 영상의 댓글이면 13407 이다.
	 * 잠금 판정이 소유자 판정보다 앞이라 아카이브된 행사에서는 두 경우 모두 13422 로 같다.
	 */
	EventVideoCommentResponseDto updateComment(long userId, long videoId, long commentId, String content);

	/** 댓글 삭제 (API 3). 행을 실제로 지운다. 멱등이 아니라 이미 지운 댓글의 재삭제는 13407 이다. */
	void deleteComment(long userId, long videoId, long commentId);

	/** 도움돼요 추가 (API 4). 이미 누른 상태에서 다시 불러도 성공하고 수가 늘지 않는다(멱등). */
	EventVideoHelpfulResponseDto addHelpful(long userId, long videoId);

	/** 도움돼요 취소 (API 5). 누른 적 없어도 성공한다(멱등). */
	EventVideoHelpfulResponseDto removeHelpful(long userId, long videoId);

	/**
	 * 댓글 목록 (API 6). cursor 는 직전 응답의 nextCursor(opaque) 다 — null 이면 첫 페이지, 무효거나 다른
	 * 영상에서 발급된 것이면 13402 다. size 는 [1, 50] 밖이면 클램프한다(0 이하·미지정은 기본 20).
	 * 아카이브된 행사에서도 조회되고, 댓글이 없으면 빈 페이지다.
	 */
	EventVideoCommentPageResponseDto getComments(long videoId, String cursor, int size);

	/**
	 * 영상 상세의 반응 재료 (API 7). 상세({@link EventVideoService#getVideoDetail})가 노출 판정을 이미
	 * 마친 뒤 부르는 읽기 전용 진입점이라 여기서 술어를 다시 걸지 않는다 — 같은 요청에서 같은 영상을 두 번
	 * 여는 왕복을 늘리지 않기 위해서다. userId 는 비로그인이면 null 이고, 이때 helpfulByMe 는 false 다.
	 */
	EventVideoDetailReactions getDetailReactions(long videoId, Long userId);

	/**
	 * 피드 한 페이지의 반응 수 (API 8). 영상 id 집합으로 group by 두 번이라 왕복이 페이지 크기와 무관하게
	 * 상수다 — 항목마다 세면 N+1 이다. 빈 컬렉션이면 조회 없이 빈 결과를 돌려준다.
	 */
	EventVideoReactionCounts countReactions(Collection<Long> videoIds);
}

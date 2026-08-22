package com.msg.fillmap.event.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.dto.EventVideoCommentPageResponseDto;
import com.msg.fillmap.event.dto.EventVideoCommentResponseDto;
import com.msg.fillmap.event.dto.EventVideoHelpfulResponseDto;
import com.msg.fillmap.event.entity.EventVideo;
import com.msg.fillmap.event.entity.EventVideoComment;
import com.msg.fillmap.event.entity.EventVideoHelpfulId;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventVideoCommentRepository;
import com.msg.fillmap.event.repository.EventVideoCommentRow;
import com.msg.fillmap.event.repository.EventVideoHelpfulRepository;
import com.msg.fillmap.event.repository.EventVideoReactionCount;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.event.support.EventVideoCommentCursor;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 행사 영상 댓글·도움돼요 구현 (MSG-441). 변경 다섯 경로의 판정 순서가 계약이다 — 영상 로드와 노출 판정
 * (13406) → 잠금 가드(13422) → (수정·삭제만) 댓글 로드와 소유자 검증(13407·13403) → 실행.
 * 가드가 소유자 검증보다 앞인 것이 핵심이다: 아카이브된 행사에서 자기 댓글과 남의 댓글의 응답이 갈리면
 * 잠금이 아니라 권한 문제로 읽힌다.
 * <p>
 * {@link EventVideoServiceImpl} 에 얹지 않고 클래스를 나눈 것은 관심사가 다르고(업로드·조회 대 반응),
 * 그쪽 생성자를 MSG-440 테스트들이 직접 호출하고 있어서다. 상세·피드는 이 서비스를 읽기로 주입받는다.
 */
@Service
public class EventVideoInteractionServiceImpl implements EventVideoInteractionService {

	/** 댓글 페이지 크기 — 피드·격자 영상 목록과 같은 규격이다. 범위 밖은 에러가 아니라 클램프. */
	private static final int PAGE_DEFAULT_SIZE = 20;
	private static final int PAGE_MAX_SIZE = 50;

	private final EventVideoRepository eventVideoRepository;
	private final EventVideoCommentRepository commentRepository;
	private final EventVideoHelpfulRepository helpfulRepository;
	// 작성·수정 응답의 작성자 닉네임 조회 (MSG-371 선례). 같은 Owner B 내부 의존이고 읽기 전용이다.
	private final VideoRepository videoRepository;
	private final Clock clock;

	/**
	 * 프로덕션 생성자 — clock 을 Clock.systemUTC() 로 고정해 전체 생성자로 위임한다 (EventVideoServiceImpl
	 * 선례). UTC 인 이유는 starts_at·ends_at 이 UTC 저장이기 때문이다 — 기본존(로컬 KST)이면 잠금 판정이
	 * 9시간 어긋난다. 전체 생성자는 경계 정각 검증용 고정 클럭 주입에 쓴다.
	 */
	@Autowired
	public EventVideoInteractionServiceImpl(
		EventVideoRepository eventVideoRepository,
		EventVideoCommentRepository commentRepository,
		EventVideoHelpfulRepository helpfulRepository,
		VideoRepository videoRepository
	) {
		this(eventVideoRepository, commentRepository, helpfulRepository, videoRepository, Clock.systemUTC());
	}

	public EventVideoInteractionServiceImpl(
		EventVideoRepository eventVideoRepository,
		EventVideoCommentRepository commentRepository,
		EventVideoHelpfulRepository helpfulRepository,
		VideoRepository videoRepository,
		Clock clock
	) {
		this.eventVideoRepository = eventVideoRepository;
		this.commentRepository = commentRepository;
		this.helpfulRepository = helpfulRepository;
		this.videoRepository = videoRepository;
		this.clock = clock;
	}

	@Override
	@Transactional
	public EventVideoCommentResponseDto createComment(long userId, long videoId, String content) {
		LocalDateTime now = LocalDateTime.now(clock);
		EventVideo link = openForWrite(videoId, now);
		// 같은 now 로 저장 시각을 채운다 — 엔티티가 스스로 시계를 읽으면 한 요청 안에서 두 시각이 갈린다.
		EventVideoComment comment = commentRepository.save(EventVideoComment.create(link, userId, content, now));
		return new EventVideoCommentResponseDto(comment.getId(), userId, nickname(userId), content,
			comment.getCreatedAt());
	}

	@Override
	@Transactional
	public EventVideoCommentResponseDto updateComment(long userId, long videoId, long commentId, String content) {
		EventVideoComment comment = openCommentForWrite(userId, videoId, commentId);
		comment.updateContent(content);   // 더티 체킹 UPDATE
		return new EventVideoCommentResponseDto(comment.getId(), userId, nickname(userId), content,
			comment.getCreatedAt());
	}

	@Override
	@Transactional
	public void deleteComment(long userId, long videoId, long commentId) {
		// 소프트 삭제가 아니라 실제 행 삭제다 — 댓글은 신고·블라인드 대상이 아니라 남겨 둘 이유가 없다.
		commentRepository.delete(openCommentForWrite(userId, videoId, commentId));
	}

	@Override
	@Transactional
	public EventVideoHelpfulResponseDto addHelpful(long userId, long videoId) {
		openForWrite(videoId, LocalDateTime.now(clock));
		// 유일성은 복합 PK 가, 멱등은 ON CONFLICT DO NOTHING 이 진다 — 반환 행 수는 성공 판정에 안 쓴다.
		helpfulRepository.insertHelpful(videoId, userId);
		return new EventVideoHelpfulResponseDto(helpfulRepository.countById_VideoId(videoId), true);
	}

	@Override
	@Transactional
	public EventVideoHelpfulResponseDto removeHelpful(long userId, long videoId) {
		openForWrite(videoId, LocalDateTime.now(clock));
		// native 1문장 DELETE — deleteById(SELECT 후 DELETE)면 동시 취소에서 진 쪽이 500 이 된다.
		helpfulRepository.deleteHelpful(videoId, userId);
		return new EventVideoHelpfulResponseDto(helpfulRepository.countById_VideoId(videoId), false);
	}

	@Override
	@Transactional(readOnly = true)
	public EventVideoCommentPageResponseDto getComments(long videoId, String cursor, int size) {
		// 조회 경로다 — 노출 판정만 하고 잠금 가드는 부르지 않는다 (FR-14).
		open(videoId, LocalDateTime.now(clock));
		return commentPage(videoId, cursor, size < 1 ? PAGE_DEFAULT_SIZE : Math.min(size, PAGE_MAX_SIZE));
	}

	@Override
	@Transactional(readOnly = true)
	public EventVideoDetailReactions getDetailReactions(long videoId, Long userId) {
		// 비로그인이면 existsById 를 아예 부르지 않는다.
		boolean helpfulByMe = userId != null
			&& helpfulRepository.existsById(new EventVideoHelpfulId(videoId, userId));
		return new EventVideoDetailReactions(
			helpfulRepository.countById_VideoId(videoId),
			helpfulByMe,
			commentRepository.countByVideo_VideoId(videoId),
			commentPage(videoId, null, PAGE_DEFAULT_SIZE));
	}

	@Override
	@Transactional(readOnly = true)
	public EventVideoReactionCounts countReactions(Collection<Long> videoIds) {
		if (videoIds.isEmpty()) {
			return new EventVideoReactionCounts(Map.of(), Map.of());
		}
		return new EventVideoReactionCounts(
			toMap(commentRepository.countCommentsByVideoIds(videoIds)),
			toMap(helpfulRepository.countHelpfulsByVideoIds(videoIds)));
	}

	private Map<Long, Long> toMap(List<EventVideoReactionCount> rows) {
		return rows.stream()
			.collect(Collectors.toMap(EventVideoReactionCount::videoId, EventVideoReactionCount::count));
	}

	/**
	 * 대상 영상 열기 — 상세와 <b>같은 술어</b>다. 행사 영상 연결이 없거나 노출 게이트(ACTIVE·PUBLIC·READY)
	 * 밖이거나 소속 회차가 아직 노출 전이면 전부 같은 404 다(소유자 본인도 예외가 아니다).
	 */
	private EventVideo open(long videoId, LocalDateTime now) {
		EventVideo link = eventVideoRepository.findVisibleWithOccurrence(videoId)
			.orElseThrow(() -> new ApiException(EventErrorCode.EVENT_VIDEO_NOT_FOUND));
		if (!link.getLocation().getOccurrence().isVisibleAt(now)) {
			throw new ApiException(EventErrorCode.EVENT_VIDEO_NOT_FOUND);
		}
		return link;
	}

	/** 변경 경로의 서두 — 노출 판정 다음에 잠금 가드다. 판정 시각은 한 요청에 하나로 고정한다. */
	private EventVideo openForWrite(long videoId, LocalDateTime now) {
		EventVideo link = open(videoId, now);
		EventLifecycleGuard.checkInteractionOpen(link.getLocation().getOccurrence(), now);
		return link;
	}

	/**
	 * 댓글 수정·삭제의 공통 서두. 순서가 계약이다 — 영상 노출(13406) → 잠금(13422) → 댓글 존재·소속
	 * (13407) → 소유자(13403). 경로의 videoId 와 댓글의 영상을 대조하는 것은 남의 영상 경로로 댓글을
	 * 건드리지 못하게 하는 것이고, 어긋나면 "없는 댓글"과 같은 응답이다.
	 */
	private EventVideoComment openCommentForWrite(long userId, long videoId, long commentId) {
		openForWrite(videoId, LocalDateTime.now(clock));
		EventVideoComment comment = commentRepository.findById(commentId)
			.filter(found -> found.getVideo().getVideoId() == videoId)
			.orElseThrow(() -> new ApiException(EventErrorCode.EVENT_COMMENT_NOT_FOUND));
		if (comment.getUserId() != userId) {
			throw new ApiException(EventErrorCode.EVENT_COMMENT_FORBIDDEN);
		}
		return comment;
	}

	/** 목록 한 장 — lookahead(size+1) 조회 → hasNext 판정·트림 → nextCursor 발급 (피드와 같은 순서). */
	private EventVideoCommentPageResponseDto commentPage(long videoId, String cursor, int pageSize) {
		// 정렬 없는 PageRequest — 정렬은 쿼리의 ORDER BY 고정이라 Pageable 이 덧붙이면 안 된다.
		Pageable page = PageRequest.of(0, pageSize + 1);
		List<EventVideoCommentRow> rows = cursor == null
			? commentRepository.findPageByVideoId(videoId, page)
			: commentRepository.findPageByVideoIdAfter(videoId, cursorId(videoId, cursor), page);
		boolean hasNext = rows.size() > pageSize;
		List<EventVideoCommentRow> pageRows = hasNext ? rows.subList(0, pageSize) : rows;
		List<EventVideoCommentResponseDto> comments = pageRows.stream()
			.map(row -> new EventVideoCommentResponseDto(row.commentId(), row.authorId(), row.authorNickname(),
				row.content(), row.createdAt()))
			.toList();
		String nextCursor = hasNext
			? EventVideoCommentCursor.encode(videoId, pageRows.get(pageRows.size() - 1).commentId())
			: null;
		return new EventVideoCommentPageResponseDto(comments, hasNext, nextCursor);
	}

	/**
	 * 무효 커서는 조용히 첫 페이지로 폴백하지 않고 400 으로 거른다 — FE 버그가 무한 첫 페이지 루프로
	 * 은폐되는 걸 막는다(피드와 같은 규칙). 다른 영상에서 발급된 커서도 같은 취급이다: 경계값이 이 영상의
	 * keyset 으로 오적용돼 결과가 조용히 잘린다.
	 */
	private long cursorId(long videoId, String cursor) {
		EventVideoCommentCursor decoded;
		try {
			decoded = EventVideoCommentCursor.decode(cursor);
		} catch (RuntimeException e) {
			throw new ApiException(EventErrorCode.INVALID_CURSOR, e);
		}
		if (decoded.videoId() != videoId) {
			throw new ApiException(EventErrorCode.INVALID_CURSOR);
		}
		return decoded.commentId();
	}

	/**
	 * 작성·수정 응답의 작성자 닉네임. 요청자는 인증된 사용자라 정상 경로에 빈손이 없다 — 빈손은 두 문장
	 * 사이에 그 계정이 탈퇴로 사라졌다는 뜻이다.
	 */
	private String nickname(long userId) {
		return videoRepository.findAuthorNickname(userId)
			.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));
	}
}

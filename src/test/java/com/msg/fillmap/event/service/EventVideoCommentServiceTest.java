package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.EventTestFixtures;
import com.msg.fillmap.event.dto.EventVideoCommentPageResponseDto;
import com.msg.fillmap.event.dto.EventVideoCommentRequestDto;
import com.msg.fillmap.event.dto.EventVideoCommentResponseDto;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventVideo;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoCommentRepository;
import com.msg.fillmap.event.repository.EventVideoHelpfulRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.event.support.EventVideoCommentCursor;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 행사 영상 댓글 CRUD 와 목록 (MSG-441 §API 1·2·3·6, 실 PostgreSQL). 소유자 판정·존재 은닉·keyset 이
 * 전부 DB 판정이라 모킹으로는 검증되지 않는다.
 * <p>
 * 격리(공유 로컬 DB): 서해 먼바다 격자(125.7)와 합성 자연키만 쓰고 {@code @Transactional} 롤백으로 정리한다.
 * 단언은 이 테스트가 만든 영상·댓글 id 로 좁혀 주변 데이터에 흔들리지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("행사 영상 댓글 (실 PostgreSQL)")
class EventVideoCommentServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	/** 서해 먼바다 기준 격자 — 다른 행사 테스트(125.0~125.6)와 겹치지 않는 대역. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.7));

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventLocationRepository locationRepository;

	@Autowired
	private EventLocationGridRepository locationGridRepository;

	@Autowired
	private EventVideoRepository eventVideoRepository;

	@Autowired
	private EventVideoCommentRepository commentRepository;

	@Autowired
	private EventVideoHelpfulRepository helpfulRepository;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private Validator validator;

	@Autowired
	private EntityManager em;

	private EventTestFixtures fixtures;
	private Long userId;

	@BeforeEach
	void setUp() {
		fixtures = new EventTestFixtures(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository);
		userId = 사용자("작성자");
	}

	private Long 사용자(String nickname) {
		return userRepository.save(User.createLocalUser(
			"msg441-comment-" + UUID.randomUUID() + "@example.com", "hash", nickname)).getId();
	}

	private EventVideoInteractionService service() {
		return new EventVideoInteractionServiceImpl(eventVideoRepository, commentRepository, helpfulRepository,
			videoRepository, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	private EventLocation 위치(long dy) {
		String gridId = (바다.gridY() + dy) + "_" + 바다.gridX();
		return fixtures.위치(fixtures.회차(fixtures.시리즈(), NOW.minusDays(1), NOW.plusDays(1), gridId), "행사 위치",
			gridId);
	}

	private Video 영상(EventLocation location, Visibility visibility, boolean 행사연결) {
		String gridId = location.getRepresentativeGridId();
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		Video video = videoRepository.save(Video.create(userId, gridId,
			"videos/original/" + UUID.randomUUID() + ".mp4", GeoSupport.toPoint(center.lat(), center.lon()),
			(short) 10, NOW, visibility));
		if (행사연결) {
			eventVideoRepository.save(new EventVideo(video, location, location.getOccurrence().getId()));
		}
		return video;
	}

	private Video 노출영상(EventLocation location) {
		Video video = 영상(location, Visibility.PUBLIC, true);
		video.markReady("videos/encoded/" + UUID.randomUUID() + ".mp4", "thumb/" + UUID.randomUUID() + ".jpg",
			video.getDurationSec());
		em.flush();
		return video;
	}

	private List<Long> 댓글ids(EventVideoCommentPageResponseDto page) {
		return page.comments().stream().map(EventVideoCommentResponseDto::commentId).toList();
	}

	@Nested
	@DisplayName("작성·수정·삭제")
	class 댓글변경 {

		// 검증: FR-EVENT-09
		@Test
		@DisplayName("진행 중 행사의 영상에 댓글을 작성하면 목록에 담긴다")
		void 진행_중_행사의_영상에_댓글을_작성하면_목록에_담긴다() {
			Video video = 노출영상(위치(0));

			EventVideoCommentResponseDto created = service().createComment(userId, video.getId(), "좋은 영상이네요");

			assertThat(created.authorId()).isEqualTo(userId);
			assertThat(created.content()).isEqualTo("좋은 영상이네요");
			assertThat(댓글ids(service().getComments(video.getId(), null, 0)))
				.containsExactly(created.commentId());
		}

		@Test
		@DisplayName("댓글 작성자만 수정할 수 있고 타인 수정은 13403이다")
		void 댓글_작성자만_수정할_수_있고_타인_수정은_13403이다() {
			Video video = 노출영상(위치(1));
			long commentId = service().createComment(userId, video.getId(), "원본").commentId();
			Long 남 = 사용자("타인");

			EventVideoCommentResponseDto updated = service()
				.updateComment(userId, video.getId(), commentId, "고친 내용");
			assertThat(updated.content()).isEqualTo("고친 내용");
			assertThat(updated.commentId()).isEqualTo(commentId);

			assertThatThrownBy(() -> service().updateComment(남, video.getId(), commentId, "남이 고침"))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_COMMENT_FORBIDDEN);
		}

		@Test
		@DisplayName("댓글 작성자만 삭제할 수 있고 삭제된 댓글의 재삭제는 13407이다")
		void 댓글_작성자만_삭제할_수_있고_삭제된_댓글의_재삭제는_13407이다() {
			Video video = 노출영상(위치(2));
			long commentId = service().createComment(userId, video.getId(), "지울 댓글").commentId();
			Long 남 = 사용자("타인");

			assertThatThrownBy(() -> service().deleteComment(남, video.getId(), commentId))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_COMMENT_FORBIDDEN);

			service().deleteComment(userId, video.getId(), commentId);
			assertThat(service().getComments(video.getId(), null, 0).comments()).isEmpty();

			// 멱등하게 만들지 않는다 — 없는 댓글 삭제 성공은 화면 상태 불일치를 감춘다.
			assertThatThrownBy(() -> service().deleteComment(userId, video.getId(), commentId))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_COMMENT_NOT_FOUND);
		}

		@Test
		@DisplayName("다른 영상의 댓글 id로 수정하면 13407이다")
		void 다른_영상의_댓글_id로_수정하면_13407이다() {
			EventLocation location = 위치(3);
			Video 대상 = 노출영상(location);
			Video 남의영상 = 노출영상(location);
			long commentId = service().createComment(userId, 남의영상.getId(), "다른 영상 댓글").commentId();

			assertThatThrownBy(() -> service().updateComment(userId, 대상.getId(), commentId, "옮겨치기"))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_COMMENT_NOT_FOUND);
		}

		@Test
		@DisplayName("빈 내용과 500자 초과 댓글은 400으로 거절된다")
		void 빈_내용과_500자_초과_댓글은_400으로_거절된다() {
			// 요청 DTO 의 @NotBlank·@Size 가 컨트롤러 @Valid 에서 400 을 만든다 — 여기서는 제약 자체를 본다.
			assertThat(validator.validate(new EventVideoCommentRequestDto("   "))).isNotEmpty();
			assertThat(validator.validate(new EventVideoCommentRequestDto("가".repeat(501)))).isNotEmpty();
			assertThat(validator.validate(new EventVideoCommentRequestDto("가".repeat(500)))).isEmpty();
		}

		@Test
		@DisplayName("노출되지 않는 영상의 댓글 작성은 13406이다 (소유자 본인 포함)")
		void 노출되지_않는_영상의_댓글_작성은_13406이다() {
			EventLocation location = 위치(4);
			Video 인코딩중 = 영상(location, Visibility.PUBLIC, true);
			Video 비공개 = 영상(location, Visibility.PRIVATE, true);
			비공개.markReady("videos/encoded/priv.mp4", "thumb/priv.jpg", 비공개.getDurationSec());
			Video 삭제 = 노출영상(location);
			삭제.markDeleted();
			Video 행사아님 = 영상(location, Visibility.PUBLIC, false);
			행사아님.markReady("videos/encoded/plain.mp4", "thumb/plain.jpg", 행사아님.getDurationSec());
			em.flush();

			// 넷 모두 소유자가 요청자 본인이다 — 상세와 글자 그대로 같은 계약이라 본인도 예외가 아니다.
			for (Video video : List.of(인코딩중, 비공개, 삭제, 행사아님)) {
				assertThatThrownBy(() -> service().createComment(userId, video.getId(), "댓글"))
					.isInstanceOf(ApiException.class)
					.extracting(e -> ((ApiException) e).getErrorCode())
					.isEqualTo(EventErrorCode.EVENT_VIDEO_NOT_FOUND);
			}
		}

		@Test
		@DisplayName("댓글 작성 시각은 요청 시각으로 저장되고 응답과 재조회가 일치한다")
		void 댓글_작성_시각은_요청_시각으로_저장되고_응답과_재조회가_일치한다() {
			Video video = 노출영상(위치(5));

			EventVideoCommentResponseDto created = service().createComment(userId, video.getId(), "시각 확인");
			em.flush();
			em.clear();

			// 팩터리가 서비스의 now 를 µs 절단해 채운다 — null 이면 NOT NULL 위반으로 작성 자체가 실패한다.
			assertThat(created.createdAt()).isEqualTo(NOW);
			assertThat(service().getComments(video.getId(), null, 0).comments())
				.singleElement()
				.extracting(EventVideoCommentResponseDto::createdAt)
				.isEqualTo(NOW);
		}
	}

	@Nested
	@DisplayName("목록과 커서")
	class 댓글목록 {

		// 검증: FR-EVENT-09
		@Test
		@DisplayName("댓글 목록은 오래된 순이고 커서로 이어진다")
		void 댓글_목록은_오래된_순이고_커서로_이어진다() {
			Video video = 노출영상(위치(6));
			long 첫째 = service().createComment(userId, video.getId(), "1").commentId();
			long 둘째 = service().createComment(userId, video.getId(), "2").commentId();
			long 셋째 = service().createComment(userId, video.getId(), "3").commentId();

			EventVideoCommentPageResponseDto first = service().getComments(video.getId(), null, 2);
			EventVideoCommentPageResponseDto second = service().getComments(video.getId(), first.nextCursor(), 2);

			// 새 댓글이 아래에 쌓이는 배열이다 (피드가 최신순인 것과 방향이 다른 의도된 차이).
			assertThat(first.hasNext()).isTrue();
			assertThat(댓글ids(first)).containsExactly(첫째, 둘째);
			assertThat(second.hasNext()).isFalse();
			assertThat(second.nextCursor()).isNull();
			assertThat(댓글ids(second)).containsExactly(셋째);
		}

		@Test
		@DisplayName("다른 영상에서 발급된 커서는 13402로 거절된다")
		void 다른_영상에서_발급된_커서는_13402로_거절된다() {
			Video video = 노출영상(위치(7));
			String 남의커서 = EventVideoCommentCursor.encode(video.getId() + 1000, 1L);

			assertThatThrownBy(() -> service().getComments(video.getId(), 남의커서, 0))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.INVALID_CURSOR);
			assertThatThrownBy(() -> service().getComments(video.getId(), "!!!not-a-cursor!!!", 0))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.INVALID_CURSOR);
		}

		// 검증: FR-EVENT-09
		@Test
		@DisplayName("댓글 목록에 작성자 닉네임과 작성 시각이 담긴다")
		void 댓글_목록에_작성자_닉네임과_작성_시각이_담긴다() {
			Video video = 노출영상(위치(8));
			Long 남 = 사용자("옆사람");
			service().createComment(남, video.getId(), "남의 댓글");
			em.flush();
			em.clear();

			assertThat(service().getComments(video.getId(), null, 0).comments())
				.singleElement()
				.satisfies(comment -> {
					assertThat(comment.authorId()).isEqualTo(남);
					assertThat(comment.authorNickname()).isEqualTo("옆사람");
					assertThat(comment.createdAt()).isEqualTo(NOW);
				});
		}

		@Test
		@DisplayName("사용자가 탈퇴하면 그 사용자의 댓글도 사라진다")
		void 사용자가_탈퇴하면_그_사용자의_댓글도_사라진다() {
			Video video = 노출영상(위치(9));
			Long 남 = 사용자("탈퇴할사람");
			service().createComment(남, video.getId(), "남의 영상에 단 댓글");
			service().createComment(userId, video.getId(), "남는 댓글");
			em.flush();

			// 탈퇴는 하드 삭제라 event_video_comments.user_id FK CASCADE 가 실제로 돈다.
			em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", 남).executeUpdate();
			em.clear();

			assertThat(service().getComments(video.getId(), null, 0).comments())
				.singleElement()
				.extracting(EventVideoCommentResponseDto::authorId)
				.isEqualTo(userId);
		}
	}
}

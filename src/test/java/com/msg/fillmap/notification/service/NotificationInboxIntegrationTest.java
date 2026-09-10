package com.msg.fillmap.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.notification.dto.NotificationPageResponseDto;
import com.msg.fillmap.notification.dto.NotificationPageResponseDto.NotificationItemResponseDto;
import com.msg.fillmap.notification.entity.Notification;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.entity.NotificationStatus;
import com.msg.fillmap.notification.exception.NotificationErrorCode;
import com.msg.fillmap.notification.repository.NotificationRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 알림함 조회·읽음 통합 검증 (실 PostgreSQL, MSG-434). 노출 조건(30일 창·수신 거부 숨김)과 keyset
 * 페이지, 읽음 멱등, outbox 상태와의 정합은 전부 실 DB 동작이라 모킹으로 대체할 수 없다.
 * 공유 로컬 DB이므로 @Transactional 격리 대신 합성 유저를 커밋해 두고 @AfterEach 에서 users 를
 * 지정 삭제한다 — notifications 는 ON DELETE CASCADE 로 함께 걷힌다
 * (NotificationCommandServiceIntegrationTest 선례). 모든 조회가 user_id 로 좁혀지므로 다른
 * 실행이 남긴 데이터와 섞이지 않는다.
 */
@SpringBootTest
@DisplayName("알림함 — 목록·안읽음 개수·읽음 처리 (실 PostgreSQL)")
class NotificationInboxIntegrationTest {

	@Autowired
	private NotificationInboxService notificationInboxService;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long me;
	private long other;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> {
			me = newUser("inbox-me");
			other = newUser("inbox-other");
		});
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id IN (:me, :other)")
				.setParameter("me", me)
				.setParameter("other", other)
				.executeUpdate());
	}

	@Nested
	@DisplayName("목록 노출 조건")
	class Visibility {

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("내 알림이 최신순으로 내려온다 — FR-1, id 내림차순")
		void 내_알림이_최신순으로_내려온다() {
			long first = record(me, "첫 알림");
			long second = record(me, "둘째 알림");
			long third = record(me, "셋째 알림");

			NotificationPageResponseDto page = notificationInboxService.getInbox(me, null, 20);

			assertThat(page.notifications()).extracting(NotificationItemResponseDto::notificationId)
				.containsExactly(third, second, first);
			assertThat(page.notifications().get(0).title()).isEqualTo("셋째 알림");
			assertThat(page.notifications().get(0).read()).isFalse();
			assertThat(page.hasNext()).isFalse();
			assertThat(page.nextCursor()).isNull();
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("타인 알림은 내 목록과 안읽음 개수에 잡히지 않는다 — FR-7 사용자 격리")
		void 타인_알림은_내_목록과_안읽음_개수에_잡히지_않는다() {
			long mine = record(me, "내 알림");
			record(other, "남의 알림");

			assertThat(notificationInboxService.getInbox(me, null, 20).notifications())
				.extracting(NotificationItemResponseDto::notificationId).containsExactly(mine);
			assertThat(notificationInboxService.getUnreadCount(me).count()).isEqualTo(1);
			assertThat(notificationInboxService.getUnreadCount(other).count()).isEqualTo(1);
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("PENDING 과 DEAD 상태 알림도 목록에 보인다 — FR-3, 발송 결과와 무관하게 받은 알림이다")
		void PENDING과_DEAD_상태_알림도_목록에_보인다() {
			long pending = record(me, "미발송", NotificationStatus.PENDING, null, now());
			long dead = record(me, "발송 실패", NotificationStatus.DEAD, "FCM 다운", now());

			assertThat(notificationInboxService.getInbox(me, null, 20).notifications())
				.extracting(NotificationItemResponseDto::notificationId).containsExactly(dead, pending);
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("토큰 없음으로 스킵된 알림도 목록에 보인다 — FR-3, 푸시를 못 받는 사용자의 유일한 경로")
		void 토큰_없음으로_스킵된_알림도_목록에_보인다() {
			long noToken = record(me, "토큰 없음", NotificationStatus.SKIPPED,
				NotificationRepository.SKIP_REASON_NO_TOKEN, now());

			assertThat(notificationInboxService.getInbox(me, null, 20).notifications())
				.extracting(NotificationItemResponseDto::notificationId).containsExactly(noToken);
			assertThat(notificationInboxService.getUnreadCount(me).count()).isEqualTo(1);
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("전송률 상한으로 스킵된 알림은 목록에 보인다 — FR-11, 사용자가 끈 것이 아니다")
		void 전송률_상한으로_스킵된_알림은_목록에_보인다() {
			long rateLimited = record(me, "상한 초과", NotificationStatus.SKIPPED,
				NotificationRepository.SKIP_REASON_RATE_LIMITED, now());

			assertThat(notificationInboxService.getInbox(me, null, 20).notifications())
				.extracting(NotificationItemResponseDto::notificationId).containsExactly(rateLimited);
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("수신 거부로 스킵된 알림은 목록과 개수에서 빠진다 — FR-11, 유일한 숨김 사유")
		void 수신_거부로_스킵된_알림은_목록과_개수에서_빠진다() {
			record(me, "설정 off", NotificationStatus.SKIPPED,
				NotificationRepository.SKIP_REASON_PREF_OFF, now());
			long visible = record(me, "보이는 알림");

			assertThat(notificationInboxService.getInbox(me, null, 20).notifications())
				.extracting(NotificationItemResponseDto::notificationId).containsExactly(visible);
			assertThat(notificationInboxService.getUnreadCount(me).count()).isEqualTo(1);
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("사유 없이 스킵된 알림도 목록에 보인다 — 숨김은 PREF_OFF 하나뿐 (INBOX_VISIBLE coalesce 방어)")
		void 사유_없이_스킵된_알림도_목록에_보인다() {
			// last_error 가 NULL 인 SKIPPED 행. coalesce 가 없으면 NOT (true AND UNKNOWN) 이 UNKNOWN 이 되어
			// 이 행이 목록과 개수 양쪽에서 조용히 사라진다 — 숨김 사유는 PREF_OFF 하나뿐이어야 한다.
			long noReason = record(me, "사유 없는 스킵", NotificationStatus.SKIPPED, null, now());

			assertThat(notificationInboxService.getInbox(me, null, 20).notifications())
				.extracting(NotificationItemResponseDto::notificationId).containsExactly(noReason);
			assertThat(notificationInboxService.getUnreadCount(me).count()).isEqualTo(1);
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("정확히 30일 전 알림은 보이고 그보다 오래된 알림은 목록과 개수에서 빠진다 — FR-10 경계")
		void 정확히_30일_전_알림은_보이고_그보다_오래된_알림은_목록과_개수에서_빠진다() {
			LocalDateTime fixedNow = LocalDateTime.of(2026, 8, 19, 12, 0);
			long onBoundary = record(me, "정확히 30일 전", NotificationStatus.SENT, null, fixedNow.minusDays(30));
			record(me, "30일 하고도 1초 전", NotificationStatus.SENT, null,
				fixedNow.minusDays(30).minusSeconds(1));
			NotificationInboxService fixedClockService = new NotificationInboxServiceImpl(
				notificationRepository, Clock.fixed(fixedNow.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

			NotificationPageResponseDto page = fixedClockService.getInbox(me, null, 20);

			assertThat(page.notifications()).extracting(NotificationItemResponseDto::notificationId)
				.containsExactly(onBoundary);
			assertThat(fixedClockService.getUnreadCount(me).count()).isEqualTo(1);
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("알림이 없으면 빈 목록이다 — FR-8")
		void 알림이_없으면_빈_목록이다() {
			NotificationPageResponseDto page = notificationInboxService.getInbox(me, null, 20);

			assertThat(page.notifications()).isEmpty();
			assertThat(page.hasNext()).isFalse();
			assertThat(page.nextCursor()).isNull();
			assertThat(notificationInboxService.getUnreadCount(me).count()).isZero();
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("MISSION_NEARBY 행은 INSERT 자체가 거부된다 — FR-9 의 전제, 목록 필터 불요 근거")
		void MISSION_NEARBY_행은_INSERT_자체가_거부된다() {
			// 이 카테고리는 서버 발송 경로가 없어 outbox 행이 생기면 결함이고, DB 가 마지막 방어선이다(V36).
			// 실제로 거부하는 것은 CHECK 이전에 컬럼 폭이다 — notifications.category 는 VARCHAR(10)(V21)이라
			// 14자 MISSION_NEARBY 가 CHECK 평가 전에 길이로 걸린다(V36 주석이 opt_outs 쪽에서 짚은 것과 같은 사정).
			// 어느 쪽이든 행이 못 생긴다는 전제는 같고, 목록에 카테고리 필터가 필요 없는 근거도 그대로다.
			assertThatThrownBy(() -> record(me, "근처 미션", NotificationCategory.MISSION_NEARBY,
				NotificationStatus.PENDING, null, now()))
				.hasStackTraceContaining("character varying(10)");

			// 폭이 나중에 넓어져도 CHECK 가 2차 방어선으로 남아야 한다 — 길이 오류만 보면 그 회귀를 놓친다.
			String checkDefinition = (String) em.createNativeQuery("""
					SELECT pg_get_constraintdef(oid) FROM pg_constraint
					WHERE conrelid = 'notifications'::regclass AND conname = 'chk_notifications_category'
					""")
				.getSingleResult();
			assertThat(checkDefinition).doesNotContain("MISSION_NEARBY").contains("BADGE");
		}
	}

	@Nested
	@DisplayName("페이지네이션 (id 내림차순 keyset)")
	class Pagination {

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("커서로 다음 페이지가 이어지고 마지막 페이지는 hasNext false 다 — FR-2")
		void 커서로_다음_페이지가_이어지고_마지막_페이지는_hasNext_false다() {
			List<Long> ids = recordMany(me, 5);   // 오름차순 id

			NotificationPageResponseDto first = notificationInboxService.getInbox(me, null, 2);
			NotificationPageResponseDto second = notificationInboxService.getInbox(me, first.nextCursor(), 2);
			NotificationPageResponseDto third = notificationInboxService.getInbox(me, second.nextCursor(), 2);

			assertThat(first.notifications()).extracting(NotificationItemResponseDto::notificationId)
				.containsExactly(ids.get(4), ids.get(3));
			assertThat(first.hasNext()).isTrue();
			assertThat(first.nextCursor()).isEqualTo(ids.get(3));
			assertThat(second.notifications()).extracting(NotificationItemResponseDto::notificationId)
				.containsExactly(ids.get(2), ids.get(1));
			assertThat(third.notifications()).extracting(NotificationItemResponseDto::notificationId)
				.containsExactly(ids.get(0));
			assertThat(third.hasNext()).isFalse();
			assertThat(third.nextCursor()).isNull();
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("size 는 0 과 음수면 20 으로 51 이면 50 으로 클램프되고 초과 1건은 hasNext 판정 후 빠진다 — D-3")
		void size는_0과_음수면_20으로_51이면_50으로_클램프되고_초과_1건은_hasNext_판정_후_빠진다() {
			recordMany(me, 52);

			assertThat(notificationInboxService.getInbox(me, null, 0).notifications()).hasSize(20);
			assertThat(notificationInboxService.getInbox(me, null, -1).notifications()).hasSize(20);
			NotificationPageResponseDto clamped = notificationInboxService.getInbox(me, null, 51);
			assertThat(clamped.notifications()).hasSize(50);   // lookahead 51번째는 hasNext 판정 후 버려진다
			assertThat(clamped.hasNext()).isTrue();
			// 남은 2건을 size 2 로 정확히 채우면 hasNext false — 경계에서 lookahead 건이 새지 않는다.
			NotificationPageResponseDto exactFit =
				notificationInboxService.getInbox(me, clamped.nextCursor(), 2);
			assertThat(exactFit.notifications()).hasSize(2);
			assertThat(exactFit.hasNext()).isFalse();
			assertThat(exactFit.nextCursor()).isNull();
		}
	}

	@Nested
	@DisplayName("읽음 처리")
	class Read {

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("행 탭 읽음 처리 후 안읽음 개수가 준다 — FR-4·FR-6")
		void 행_탭_읽음_처리_후_안읽음_개수가_준다() {
			long target = record(me, "읽을 알림");
			record(me, "안 읽을 알림");

			notificationInboxService.markRead(me, target);

			assertThat(notificationInboxService.getUnreadCount(me).count()).isEqualTo(1);
			assertThat(notificationInboxService.getInbox(me, null, 20).notifications())
				.filteredOn(item -> item.notificationId() == target)
				.singleElement().extracting(NotificationItemResponseDto::read).isEqualTo(true);
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("이미 읽은 알림을 다시 읽음 처리해도 성공하고 최초 읽음 시각이 보존된다 — FR-4 멱등, coalesce")
		void 이미_읽은_알림을_다시_읽음_처리해도_성공하고_최초_읽음_시각이_보존된다() {
			long target = record(me, "두 번 탭할 알림");
			notificationInboxService.markRead(me, target);
			LocalDateTime firstReadAt = reload(target).getReadAt();

			notificationInboxService.markRead(me, target);   // 예외 없이 성공해야 한다

			assertThat(firstReadAt).isNotNull();
			assertThat(reload(target).getReadAt()).isEqualTo(firstReadAt);
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("타인 알림을 읽음 처리하면 존재하지 않는 알림과 같은 10404 다 — FR-7")
		void 타인_알림을_읽음_처리하면_존재하지_않는_알림과_같은_10404다() {
			long othersNotification = record(other, "남의 알림");

			assertThatThrownBy(() -> notificationInboxService.markRead(me, othersNotification))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.NOTIFICATION_NOT_FOUND);
			assertThatThrownBy(() -> notificationInboxService.markRead(me, -1L))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.NOTIFICATION_NOT_FOUND);
			// 남의 알림은 읽음으로도 바뀌지 않아야 한다.
			assertThat(reload(othersNotification).getReadAt()).isNull();
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("모두 읽음 후 안읽음 개수가 0 이고 빨간 점 판정 재료가 사라진다 — FR-5·FR-6")
		void 모두_읽음_후_안읽음_개수가_0이고_빨간_점_판정_재료가_사라진다() {
			recordMany(me, 3);
			long othersNotification = record(other, "남의 알림");

			notificationInboxService.markAllRead(me);

			assertThat(notificationInboxService.getUnreadCount(me).count()).isZero();
			assertThat(notificationInboxService.getInbox(me, null, 20).notifications())
				.extracting(NotificationItemResponseDto::read).containsOnly(true);
			assertThat(reload(othersNotification).getReadAt()).isNull();   // 남의 알림은 그대로
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("안읽은 알림이 없어도 모두 읽음은 성공한다 — FR-5 멱등")
		void 안읽은_알림이_없어도_모두_읽음은_성공한다() {
			notificationInboxService.markAllRead(me);   // 알림 0건

			record(me, "알림 하나");
			notificationInboxService.markAllRead(me);
			notificationInboxService.markAllRead(me);   // 전부 읽은 상태에서 재요청

			assertThat(notificationInboxService.getUnreadCount(me).count()).isZero();
		}
	}

	@Nested
	@DisplayName("outbox 상태와의 정합")
	class OutboxIndependence {

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("읽음 처리가 outbox status 를 바꾸지 않는다 — read_at 만 만지는 부분 UPDATE (D-5)")
		void 읽음_처리가_outbox_status를_바꾸지_않는다() {
			long pending = record(me, "미발송", NotificationStatus.PENDING, null, now());

			notificationInboxService.markRead(me, pending);
			notificationInboxService.markAllRead(me);

			Notification row = reload(pending);
			assertThat(row.getStatus()).isEqualTo(NotificationStatus.PENDING);
			assertThat(row.getRetryCount()).isZero();
			assertThat(row.getLastError()).isNull();
			assertThat(row.getSentAt()).isNull();
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("읽음 처리된 행에 markSent 나 markSkipped 전이가 일어나도 read_at 이 보존된다 — 반대 방향")
		void 읽음_처리된_행에_markSent나_markSkipped_전이가_일어나도_read_at이_보존된다() {
			// 종결 전이의 술어가 PENDING·PUBLISHED 만 통과시키므로 미종결 상태로 세팅해야 실제로 전이가 일어난다.
			long toSend = record(me, "발송될 알림", NotificationStatus.PENDING, null, now());
			long toSkip = record(me, "스킵될 알림", NotificationStatus.PENDING, null, now());
			notificationInboxService.markRead(me, toSend);
			notificationInboxService.markRead(me, toSkip);
			LocalDateTime sentReadAt = reload(toSend).getReadAt();
			LocalDateTime skipReadAt = reload(toSkip).getReadAt();

			tx.executeWithoutResult(status -> {
				notificationRepository.markSent(toSend);
				notificationRepository.markSkipped(toSkip, NotificationRepository.SKIP_REASON_RATE_LIMITED);
			});

			assertThat(reload(toSend).getStatus()).isEqualTo(NotificationStatus.SENT);
			assertThat(reload(toSend).getReadAt()).isEqualTo(sentReadAt);
			assertThat(reload(toSkip).getStatus()).isEqualTo(NotificationStatus.SKIPPED);
			assertThat(reload(toSkip).getReadAt()).isEqualTo(skipReadAt);
		}
	}

	// ── 헬퍼 ──

	private static LocalDateTime now() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	private long newUser(String prefix) {
		String email = prefix + "-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "알림함테스터")).getId();
	}

	private long record(long userId, String title) {
		return record(userId, title, NotificationStatus.SENT, null, now());
	}

	private long record(long userId, String title, NotificationStatus status, String lastError,
		LocalDateTime createdAt) {
		return record(userId, title, NotificationCategory.BADGE, status, lastError, createdAt);
	}

	/**
	 * 발송 결과별 행을 직접 만든다 — 컨슈머를 돌리지 않고 status·last_error·created_at 을 조합해야
	 * 노출 조건과 30일 경계를 결정적으로 검증할 수 있다. event_key 는 실행 간 UNIQUE 충돌을 피해 nanoTime.
	 */
	private long record(long userId, String title, NotificationCategory category, NotificationStatus status,
		String lastError, LocalDateTime createdAt) {
		return tx.execute(txStatus -> ((Number) em.createNativeQuery("""
				INSERT INTO notifications (user_id, category, event_key, title, body, status, last_error, created_at)
				VALUES (:userId, :category, :eventKey, :title, :body, :status, :lastError, :createdAt)
				RETURNING id
				""")
			.setParameter("userId", userId)
			.setParameter("category", category.name())
			.setParameter("eventKey", "INBOX:" + System.nanoTime())
			.setParameter("title", title)
			.setParameter("body", title + " 본문")
			.setParameter("status", status.name())
			.setParameter("lastError", lastError)
			.setParameter("createdAt", createdAt)
			.getSingleResult()).longValue());
	}

	/** id 오름차순으로 count 건. 반환 리스트의 마지막이 가장 최신이다. */
	private List<Long> recordMany(long userId, int count) {
		return IntStream.rangeClosed(1, count)
			.mapToObj(i -> record(userId, "알림 " + i))
			.toList();
	}

	/** 영속성 컨텍스트를 비우고 다시 읽는다 — bulk UPDATE 는 1차 캐시를 갱신하지 않는다. */
	private Notification reload(long id) {
		em.clear();
		return notificationRepository.findById(id).orElseThrow();
	}
}

package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.global.exception.ApiException;

/**
 * 생명주기 가드 판정표 (MSG-442, PRD §4.2). 고정 Clock 단위 테스트로 4상태 × 2메서드와 경계 정각을
 * 전수 확인한다 — 440·441 은 호출 존재만 자기 테스트에서 확인하면 되도록 여기서 규칙 검증을 끝낸다.
 * <p>
 * 상호작용 규칙은 2026-08-21 에 "종료 정각부터 잠금"에서 <b>"아카이브 전환(종료 + 30일)부터 잠금"</b>으로
 * 뒤집혔다(정민 확정, MSG-441 구현 중). 두 메서드가 이제 같은 창(시작 ~ 종료 + 30일)을 보므로 마감 정각
 * 한 점에서 함께 닫힌다 — 업로드 규칙 자체는 바뀌지 않았다.
 */
@DisplayName("EventLifecycleGuard 상태별 차단 규칙")
class EventLifecycleGuardTest {

	private static final LocalDateTime 시작 = LocalDateTime.of(2026, 10, 6, 1, 0);
	private static final LocalDateTime 종료 = LocalDateTime.of(2026, 10, 15, 13, 0);
	private static final LocalDateTime 마감 = 종료.plusDays(EventOccurrence.UPLOAD_GRACE_DAYS);

	private static final EventOccurrence 회차 = 회차();

	private static EventOccurrence 회차() {
		EventOccurrence occurrence = new EventOccurrence(null, "msg442-guard");
		occurrence.update(null, "제목", "부산", 시작, 종료, 1, 2, 3, 4);
		return occurrence;
	}

	private EventLifecycleGuard 가드(LocalDateTime now) {
		return new EventLifecycleGuard(Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	private ThrowingCallable 업로드(LocalDateTime now) {
		return () -> 가드(now).checkUploadOpen(회차);
	}

	private ThrowingCallable 상호작용(LocalDateTime now) {
		return () -> 가드(now).checkInteractionOpen(회차);
	}

	@Nested
	@DisplayName("업로드")
	class Upload {

		// 검증: FR-EVENT-10 — 예정 상태 행사 경유 업로드 차단 (2026-08-21 사용자 확정)
		@Test
		@DisplayName("예정 상태의 행사 경유 업로드는 시작 전 에러로 거절된다")
		void 예정_상태의_행사_경유_업로드는_시작_전_에러로_거절된다() {
			assertThatThrownBy(업로드(시작.minusNanos(1)))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.EVENT_UPLOAD_NOT_STARTED);
		}

		// 검증: FR-EVENT-10 — 업로드 유예 30일 (PRD 요구 12)
		@Test
		@DisplayName("진행 중과 업로드 유예에는 업로드가 허용된다 — 종료 후 30일 유예")
		void 진행_중과_업로드_유예에는_업로드가_허용된다() {
			assertThatCode(업로드(시작)).doesNotThrowAnyException();
			assertThatCode(업로드(종료.minusNanos(1))).doesNotThrowAnyException();
			assertThatCode(업로드(종료)).doesNotThrowAnyException();
			assertThatCode(업로드(마감.minusNanos(1))).doesNotThrowAnyException();
		}

		// 검증: FR-EVENT-10 — 아카이브 업로드 차단 (PRD 요구 15)
		@Test
		@DisplayName("아카이브에서는 업로드가 마감 에러로 거절된다")
		void 아카이브에서는_업로드가_마감_에러로_거절된다() {
			assertThatThrownBy(업로드(마감))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.EVENT_UPLOAD_CLOSED);
		}
	}

	@Nested
	@DisplayName("댓글·도움돼요")
	class Interaction {

		@Test
		@DisplayName("예정과 진행 중과 업로드 유예에는 댓글과 도움돼요 변경이 허용된다")
		void 예정과_진행_중과_업로드_유예에는_댓글과_도움돼요_변경이_허용된다() {
			assertThatCode(상호작용(시작.minusNanos(1))).doesNotThrowAnyException();
			assertThatCode(상호작용(시작)).doesNotThrowAnyException();
			assertThatCode(상호작용(종료.minusNanos(1))).doesNotThrowAnyException();
			// 2026-08-21 번복: 유예 기간은 "행사 다녀와서 나중에 올리기"용이라 그 영상이 반응을 못 받으면
			// 유예의 목적과 결과가 서로 깎인다. 업로드 창과 같은 구간을 본다.
			assertThatCode(상호작용(종료)).doesNotThrowAnyException();
			assertThatCode(상호작용(마감.minusNanos(1))).doesNotThrowAnyException();
		}

		// 검증: FR-EVENT-10 — 아카이브 전환부터 상호작용 잠금 (PRD 요구 13, 2026-08-21 번복)
		@Test
		@DisplayName("아카이브 전환부터 댓글과 도움돼요 변경이 잠긴다")
		void 아카이브_전환부터_댓글과_도움돼요_변경이_잠긴다() {
			assertThatThrownBy(상호작용(마감))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.EVENT_INTERACTION_LOCKED);
		}
	}

	// 검증: FR-EVENT-10 — 서버 시각 기준 반개구간 경계 (PRD 요구 22)
	@Test
	@DisplayName("경계 정각은 다음 상태로 판정된다 — 시작·종료·마감 3점의 반개구간 규칙")
	void 경계_정각은_다음_상태로_판정된다() {
		assertThatCode(업로드(시작)).doesNotThrowAnyException();                  // 시작 정각 = LIVE
		assertThatCode(상호작용(종료)).doesNotThrowAnyException();                // 종료 정각 = UPLOAD_GRACE
		assertThatCode(업로드(종료)).doesNotThrowAnyException();
		assertThatThrownBy(업로드(마감)).isInstanceOf(ApiException.class);        // 마감 정각 = ARCHIVED
		assertThatThrownBy(상호작용(마감)).isInstanceOf(ApiException.class);      // 두 규칙이 같은 정각에서 닫힌다
	}
}

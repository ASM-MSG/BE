package com.msg.fillmap.event.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventStatus;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.global.exception.ApiException;

/**
 * 행사 생명주기 차단 규칙 (MSG-442, PRD §4.2 상태 표 · FR-12·13·15·22). 업로드(MSG-440)와 댓글·도움돼요
 * (MSG-441)가 쓰기 경로 서두에서 호출하는 공용 계약이다 — 판정이 흩어지면 경계 정각의 해석이 API 마다
 * 갈라지므로 한 곳에 둔다.
 * <p>
 * 시각 비교식이 여기 없다. 경계 정의의 정본은 {@link EventOccurrence#statusAt} 하나이고 이 가드는 그
 * 결과를 상태별 허용 여부로 옮길 뿐이다. 유예 기간에 올라간 영상이 처음부터 댓글·도움돼요 불가인 것
 * (US-009)도 별도 로직이 아니다 — 잠금이 영상이 아니라 회차 상태의 파생이기 때문이다.
 * <p>
 * 조회 메서드가 없는 것도 규칙이다 (FR-14) — 어느 상태에서도 조회는 막지 않는다.
 */
@Component
public class EventLifecycleGuard {

	private final Clock clock;

	/** 프로덕션 생성자 — starts_at·ends_at 이 UTC 저장이라 기본존(로컬 KST)이면 판정이 9시간 어긋난다. */
	@Autowired
	public EventLifecycleGuard() {
		this(Clock.systemUTC());
	}

	/** 경계 정각 검증용 고정 Clock 주입 (EventViewerServiceImpl 선례). */
	public EventLifecycleGuard(Clock clock) {
		this.clock = clock;
	}

	/**
	 * 행사 경유 업로드 가능 구간은 시작부터 종료 30일 후까지다 (FR-12·FR-15, 2026-08-21 확정).
	 * 시작 전 차단이 마감과 다른 코드인 것은 FE 가 버튼 비활성화 근거를 구분해야 하기 때문이다.
	 */
	public void checkUploadOpen(EventOccurrence occurrence) {
		EventStatus status = occurrence.statusAt(LocalDateTime.now(clock));
		if (status == EventStatus.UPCOMING) {
			throw new ApiException(EventErrorCode.EVENT_UPLOAD_NOT_STARTED);
		}
		if (status == EventStatus.ARCHIVED) {
			throw new ApiException(EventErrorCode.EVENT_UPLOAD_CLOSED);
		}
	}

	/** 댓글·도움돼요 변경은 종료 시점부터 잠긴다 (FR-13). 예정 상태는 열려 있다 (PRD §4.2). */
	public void checkInteractionOpen(EventOccurrence occurrence) {
		EventStatus status = occurrence.statusAt(LocalDateTime.now(clock));
		if (status == EventStatus.UPLOAD_GRACE || status == EventStatus.ARCHIVED) {
			throw new ApiException(EventErrorCode.EVENT_INTERACTION_LOCKED);
		}
	}
}

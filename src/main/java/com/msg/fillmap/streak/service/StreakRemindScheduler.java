package com.msg.fillmap.streak.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.streak.repository.StreakRemindProjection;
import com.msg.fillmap.streak.repository.StreakRepository;

/**
 * 스트릭 리마인드 배치 (MSG-181 D9·D10). 매일 20시 KST 첫 시도 + 21~23시 시간별 재발화 — 리마인드는
 * 자정 전에만 의미 있는 시간 민감 알림이라, 부분 실패분의 당일 재시도를 별도 코드 없이 재발화가 맡는다.
 * 이미 기록된 대상은 event_key(REMIND:{KST일자})의 ON CONFLICT 0행 + 컨슈머 1건/일 상한이 흡수하므로
 * 중복 발송이 없다(멱등이 곧 재시도 메커니즘, Codex P2). 20시 근거: 끊김 경계(KST 자정)까지 4시간 여유,
 * 저녁 여가 시간대, 기존 배치(04시 스테일 토큰)와 분산. 대상 판정("지킬 스트릭이 있는 마지막 날")은
 * 스트릭 도메인 지식이라 streak/service 에 산다 (AiBlurPoller 선례). 사용자별 record 는 호출마다
 * 자체 트랜잭션이고 실패는 사용자 단위로 격리한다 — 한 명의 실패가 나머지 대상을 막지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fillmap.notification", name = "enabled")
public class StreakRemindScheduler {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final StreakRepository streakRepository;
	private final NotificationCommandService notificationCommandService;
	private final Clock clock;

	@Autowired
	public StreakRemindScheduler(StreakRepository streakRepository,
		NotificationCommandService notificationCommandService) {
		this(streakRepository, notificationCommandService, Clock.systemUTC());
	}

	/** KST 일자 경계 결정적 테스트용 — 고정 Clock 주입 (HotScoreCommandServiceImpl 선례). */
	public StreakRemindScheduler(StreakRepository streakRepository,
		NotificationCommandService notificationCommandService, Clock clock) {
		this.streakRepository = streakRepository;
		this.notificationCommandService = notificationCommandService;
		this.clock = clock;
	}

	@Scheduled(cron = "0 0 20-23 * * *", zone = "Asia/Seoul")
	public void remind() {
		// 어제는 앱이 KST 계산해 바인딩 (D8) — 세션 TZ 캐스트 스큐(MSG-222 실측) 재발 방지.
		LocalDate today = LocalDate.ofInstant(clock.instant(), KST);
		int failed = 0;
		Exception lastError = null;
		for (StreakRemindProjection target : streakRepository.findRemindTargets(today.minusDays(1))) {
			try {
				notificationCommandService.record(target.getUserId(), NotificationCategory.REMIND,
					"REMIND:" + today, "스트릭이 오늘 자정에 끊겨요",
					target.getCurrentCount() + "일 연속 기록 중이에요. 영상 하나만 올리면 스트릭이 이어져요");
			} catch (Exception e) {
				failed++;   // 사용자 단위 격리 — 한 명 실패가 나머지 대상을 막지 않는다
				lastError = e;
			}
		}
		if (failed > 0) {
			log.warn("리마인드 기록 실패 {}건 — 다음 시간별 재발화(21~23시)가 당일 재시도한다, dedupe 가 중복 흡수",
				failed, lastError);
		}
	}
}

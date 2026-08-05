package com.msg.fillmap.streak.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.streak.repository.StreakRemindProjection;
import com.msg.fillmap.streak.repository.StreakRepository;

/**
 * 스트릭 리마인드 배치 (MSG-181 D9·D10). 매일 20:00 KST — 끊김 경계(KST 자정)까지 4시간 여유,
 * 저녁 여가 시간대, 기존 배치(04시 스테일 토큰)와 분산. 대상 판정("지킬 스트릭이 있는 마지막 날")은
 * 스트릭 도메인 지식이라 streak/service 에 산다 (AiBlurPoller 선례). 사용자별 record 는 호출마다
 * 자체 트랜잭션 — 중간 크래시 시 재실행이 event_key(REMIND:{KST일자}) dedupe 로 이어붙는(멱등) 쪽이
 * 전량 롤백-재시작보다 단순·안전하다. 같은 날 재실행도 같은 키의 ON CONFLICT 가 0행으로 흡수한다.
 */
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

	@Scheduled(cron = "0 0 20 * * *", zone = "Asia/Seoul")
	public void remind() {
		// 어제는 앱이 KST 계산해 바인딩 (D8) — 세션 TZ 캐스트 스큐(MSG-222 실측) 재발 방지.
		LocalDate today = LocalDate.ofInstant(clock.instant(), KST);
		for (StreakRemindProjection target : streakRepository.findRemindTargets(today.minusDays(1))) {
			notificationCommandService.record(target.getUserId(), NotificationCategory.REMIND,
				"REMIND:" + today, "스트릭이 오늘 자정에 끊겨요",
				target.getCurrentCount() + "일 연속 기록 중이에요. 영상 하나만 올리면 스트릭이 이어져요");
		}
	}
}

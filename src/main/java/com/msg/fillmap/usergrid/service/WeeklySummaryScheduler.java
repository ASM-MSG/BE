package com.msg.fillmap.usergrid.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.usergrid.repository.UserGridRepository;
import com.msg.fillmap.usergrid.repository.WeeklyActivityProjection;

/**
 * 주간 활동 요약 배치 (MSG-315 D1·D2·D5). 일요일 19시·19시 30분 KST — 19시대인 이유는 스트릭 리마인드의
 * 재발화 창(20~23시)을 통째로 피하기 때문이고, 30분 뒤 재발화는 부분 실패의 같은 주 재시도다
 * (이미 기록된 대상은 event_key WEEKLY:{ISO 주차} 의 ON CONFLICT 0행이 흡수 — 멱등이 곧 재시도, MSG-181 선례).
 * 집계 창은 KST 이번 주 월요일 0시부터 실행 시각까지 — 지금 끝나가는 주라 사용자가 받는 문장과 기간이 맞는다.
 * 대가로 일요일 19시 이후 활동은 어떤 요약에도 안 잡히며(넛지는 보장이 아니라 기회, MSG-314 D3 승계),
 * 도감·스트릭 같은 실제 기록에는 당연히 그대로 남는다. 대상 판정이 도감·영상 활동 지식이라 usergrid 에 산다
 * (StreakRemindScheduler 선례). 사용자별 record 는 호출마다 자체 트랜잭션이고 실패는 사용자 단위로 격리한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fillmap.notification", name = "enabled")
public class WeeklySummaryScheduler {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final UserGridRepository userGridRepository;
	private final NotificationCommandService notificationCommandService;
	private final Clock clock;

	@Autowired
	public WeeklySummaryScheduler(UserGridRepository userGridRepository,
		NotificationCommandService notificationCommandService) {
		this(userGridRepository, notificationCommandService, Clock.systemUTC());
	}

	/** KST 주 경계 결정적 테스트용 — 고정 Clock 주입 (StreakRemindScheduler 선례). */
	public WeeklySummaryScheduler(UserGridRepository userGridRepository,
		NotificationCommandService notificationCommandService, Clock clock) {
		this.userGridRepository = userGridRepository;
		this.notificationCommandService = notificationCommandService;
		this.clock = clock;
	}

	@Scheduled(cron = "0 0,30 19 * * SUN", zone = "Asia/Seoul")
	public void summarize() {
		LocalDate todayKst = LocalDate.ofInstant(clock.instant(), KST);
		ZonedDateTime weekStartKst = todayKst.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
			.atStartOfDay(KST);
		// 집계 재료(videos.created_at·user_grids.first_collected_at)는 UTC 벽시계로 저장된다 — 두 컬럼의
		// 쓰기 경로를 UTC 로 고정한 MSG-376 후속 수정 이후의 사실이다(그 전에는 JVM 기본 존이었고 이 환산도
		// systemDefault 였다). KST JVM 에서 systemDefault 로 바인딩하면 이제 창이 9시간 어긋난다.
		LocalDateTime weekStart = weekStartKst.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		String eventKey = "WEEKLY:%d-W%02d".formatted(
			todayKst.get(IsoFields.WEEK_BASED_YEAR), todayKst.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));

		int failed = 0;
		Exception lastError = null;
		for (WeeklyActivityProjection target : userGridRepository.findWeeklyActivity(weekStart, now)) {
			try {
				notificationCommandService.record(target.getUserId(), NotificationCategory.WEEKLY,
					eventKey, "이번 주 활동 요약", body(target));
			} catch (Exception e) {
				failed++;   // 사용자 단위 격리 — 한 명 실패가 나머지 대상을 막지 않는다
				lastError = e;
			}
		}
		if (failed > 0) {
			log.warn("주간 요약 기록 실패 {}건 — 19시 30분 재발화가 같은 주 안에서 재시도한다, dedupe 가 중복 흡수",
				failed, lastError);
		}
	}

	/**
	 * 재방문 업로드만 한 주(격자 0)는 "새 격자 0개"라고 말하지 않는다 (D5). 반대 경우(격자 ≥ 1, 영상 0)는
	 * 도달할 수 없어 분기가 없다 — 이번 주 도감 행이 남아 있으면 이번 주 미삭제 영상이 최소 하나다.
	 */
	private String body(WeeklyActivityProjection target) {
		if (target.getGridCount() < 1) {
			return "영상 %d개를 올렸어요. 다음 주에는 새 격자도 채워 볼까요?".formatted(target.getVideoCount());
		}
		return "새 격자 %d개를 수집하고 영상 %d개를 올렸어요. 도감에서 확인해 보세요"
			.formatted(target.getGridCount(), target.getVideoCount());
	}
}

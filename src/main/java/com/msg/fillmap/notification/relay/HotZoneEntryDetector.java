package com.msg.fillmap.notification.relay;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.hotzone.service.HotZoneService;
import com.msg.fillmap.hotzone.service.HotZoneView;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.usergrid.service.GridOccupantView;
import com.msg.fillmap.usergrid.service.UserGridQueryService;

/**
 * 핫구역 진입 검출 스케줄러 (MSG-181 D3~D5·D7). 핫스코어는 조회 시점 계산이라 "진입 순간" 이벤트가
 * 없다 — 10분 주기로 현재 핫구역 집합과 직전 스냅숏(Redis SET)을 비교해 진입분만 산출하고, 진입
 * 격자를 점령한 사용자 전원에게 HOTZONE 알림을 기록한다. 비즈니스 상태 변경이 없는 트리거라 record
 * 자체 트랜잭션으로 충분(179 D6). 스냅숏 갱신은 반드시 record 이후 — 사이 크래시는 다음 사이클
 * 재검출을 event_key dedupe 가 흡수한다(at-least-once). 사이클 전체 catch 로 폴러는 죽지 않는다
 * (AiBlurPoller·릴레이 선례). 같은 날 재진입은 event_key(HOTZONE:{KST일자}:{gridId}) dedupe, 여러
 * 격자 동시 진입의 발송 캡은 컨슈머의 1건/일 상한 몫(FR-12) — 트리거에서 중복 억제를 또 하지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fillmap.notification", name = "enabled")
public class HotZoneEntryDetector {

	/** 10분 주기 (D3) — 연성 실시간 + 1건/일 상한이라 더 촘촘해도 발송이 안 는다. 테스트는 detect() 직접 호출. */
	private static final long DETECT_INTERVAL_MS = 600_000L;
	private static final String SNAPSHOT_KEY = "notification:hotzone:last";
	// ponytail: 한국 전역 bbox 상수(마라도~독도 커버, D4) — 해외 확장 시 전국 조회 계약(getHotGridIds) 신설로 교체
	private static final ViewportBounds KOREA_BOUNDS = new ViewportBounds(33.0, 124.0, 39.5, 132.0);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final HotZoneService hotZoneService;
	private final UserGridQueryService userGridQueryService;
	private final NotificationCommandService notificationCommandService;
	private final StringRedisTemplate redisTemplate;
	private final Clock clock;

	@Autowired
	public HotZoneEntryDetector(HotZoneService hotZoneService, UserGridQueryService userGridQueryService,
		NotificationCommandService notificationCommandService, StringRedisTemplate redisTemplate) {
		this(hotZoneService, userGridQueryService, notificationCommandService, redisTemplate, Clock.systemUTC());
	}

	/** KST 일자 경계 결정적 테스트용 — 고정 Clock 주입 (HotScoreCommandServiceImpl 선례). */
	public HotZoneEntryDetector(HotZoneService hotZoneService, UserGridQueryService userGridQueryService,
		NotificationCommandService notificationCommandService, StringRedisTemplate redisTemplate, Clock clock) {
		this.hotZoneService = hotZoneService;
		this.userGridQueryService = userGridQueryService;
		this.notificationCommandService = notificationCommandService;
		this.redisTemplate = redisTemplate;
		this.clock = clock;
	}

	@Scheduled(fixedDelay = DETECT_INTERVAL_MS)
	public void detect() {
		try {
			Set<String> current = new HashSet<>();
			for (HotZoneView hotZone : hotZoneService.getHotZones(KOREA_BOUNDS)) {
				current.add(hotZone.gridId());
			}
			Set<String> previous = redisTemplate.opsForSet().members(SNAPSHOT_KEY);
			String today = LocalDate.ofInstant(clock.instant(), KST).toString();
			for (String gridId : current) {
				if (previous != null && previous.contains(gridId)) {
					continue;   // 유지 중 — 진입 전이만 통지 (FR-10)
				}
				notifyOccupants(gridId, today);
			}
			// 스냅숏 갱신은 record 이후 (D3 순서 — 역순이면 크래시 시 유실). DEL 후 SADD, 空이면 DEL 만 (D5).
			redisTemplate.delete(SNAPSHOT_KEY);
			if (!current.isEmpty()) {
				redisTemplate.opsForSet().add(SNAPSHOT_KEY, current.toArray(String[]::new));
			}
		} catch (Exception e) {
			// 스냅숏 미갱신 유지 — 다음 사이클이 전량 재검출하고 event_key dedupe 가 중복을 흡수한다.
			log.warn("핫구역 진입 검출 실패 — 이번 사이클 건너뜀(다음 주기 재검출)", e);
		}
	}

	/** 진입 격자의 점령 사용자 전원 기록 — 행정동 없는 격자(해상 등)는 폴백 문구 (D7). */
	private void notifyOccupants(String gridId, String today) {
		String eventKey = "HOTZONE:" + today + ":" + gridId;
		for (GridOccupantView occupant : userGridQueryService.getGridOccupants(gridId)) {
			String body = occupant.regionName() == null
				? "내가 수집한 격자가 지금 인기예요. 지도에서 확인해 보세요"
				: occupant.regionName() + "에서 수집한 격자가 지금 인기예요. 지도에서 확인해 보세요";
			notificationCommandService.record(occupant.userId(), NotificationCategory.HOTZONE, eventKey,
				"내 격자가 핫구역에 들어왔어요", body);
		}
	}
}

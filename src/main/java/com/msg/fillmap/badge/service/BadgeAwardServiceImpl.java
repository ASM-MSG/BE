package com.msg.fillmap.badge.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.entity.BadgeConditionType;
import com.msg.fillmap.badge.repository.BadgeRepository;
import com.msg.fillmap.badge.repository.EligibleBadgeProjection;
import com.msg.fillmap.badge.repository.UserBadgeRepository;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.repository.NotificationRepository;
import com.msg.fillmap.notification.service.NotificationCommandService;

/**
 * 뱃지 판정·지급 엔진 (MSG-239). "조건을 충족했는데 아직 못 받은 뱃지"를 조회한 뒤 지급 INSERT 하는
 * 2단 구조 — 대부분의 호출은 1단에서 빈 결과로 끝나 비용이 조회 수준이다. 반환 리스트에는 INSERT 가
 * 실제로 성공한(영향 행 1) 뱃지만 담는다: 동시 요청 경합에서 상대 트랜잭션이 같은 뱃지를 먼저
 * 지급했다면 내 INSERT 는 ON CONFLICT 로 0 을 받고, 그 뱃지는 "이 요청으로 새로 획득한" 게 아니므로
 * 응답에서 제외한다. PostgreSQL 은 conflict 판정 전에 선행 트랜잭션 커밋을 기다리므로 이 필터는
 * 경합에서도 안전하다. 상세 결정: docs/MSG-239.md §D5 (+ Codex 교차 리뷰로 성공분만 반환으로 강화).
 *
 * <p>지급 루프 뒤에는 임박 판정(MSG-314)이 이어진다 — 다음 티어까지 정확히 1 남은 순간을 잡아
 * BADGE_NEAR 알림을 같은 커밋에 기록한다. 획득(value <= metric)과 임박(value = metric + 1)은
 * 서로소라 한 뱃지가 둘 다에 걸릴 수 없다.
 */
@Service
@RequiredArgsConstructor
public class BadgeAwardServiceImpl implements BadgeAwardService {

	// 임박 알림 이벤트 키 접두 (MSG-314 D2) — "{접두}{badgeId}" 가 생애 1회 축, 접두 LIKE 검색이 하루 1건 축.
	private static final String NEAR_EVENT_PREFIX = "BADGE_NEAR:";
	// 임박 판정 축 allowlist (MSG-314 D1) — 정수 개수 축만. REGION_PERCENT 만 막는 blocklist 면 SPECIAL 이
	// 숫자 임박 쿼리에 흘러들고, enum 에 축이 추가될 때 기본이 "포함"이 된다 (Codex 교차 리뷰 1R P2).
	private static final Set<BadgeConditionType> NEAR_MISS_TYPES = EnumSet.of(
		BadgeConditionType.UPLOAD_COUNT, BadgeConditionType.TOTAL_GRIDS,
		BadgeConditionType.STREAK_DAYS, BadgeConditionType.MISSION_COUNT);
	// 일 경계 = KST 자정 (glossary 스트릭 규칙, NotificationConsumer.rateLimited D8 선례).
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final BadgeRepository badgeRepository;
	private final UserBadgeRepository userBadgeRepository;
	private final NotificationCommandService notificationCommandService;
	private final NotificationRepository notificationRepository;
	private final Clock clock;

	/**
	 * 프로덕션 생성자 — 마지막 인자 clock 을 Clock.systemUTC() 로 고정해 Lombok 전체 생성자로 위임한다
	 * (VideoServiceImpl 선례). 임박 하루 1건의 KST 오늘 자정 산출에 쓰며, 전체 생성자는 테스트 고정
	 * 클럭 주입용이다.
	 */
	@Autowired
	public BadgeAwardServiceImpl(BadgeRepository badgeRepository, UserBadgeRepository userBadgeRepository,
		NotificationCommandService notificationCommandService, NotificationRepository notificationRepository) {
		this(badgeRepository, userBadgeRepository, notificationCommandService, notificationRepository,
			Clock.systemUTC());
	}

	@Override
	@Transactional
	public List<EarnedBadgeResponseDto> awardUploadBadges(long userId) {
		return award(userId, BadgeConditionType.UPLOAD_COUNT, userBadgeRepository.countMyVideos(userId));
	}

	@Override
	@Transactional
	public List<EarnedBadgeResponseDto> awardCollectionBadges(long userId, String gridId) {
		List<EarnedBadgeResponseDto> earned = new ArrayList<>(
			award(userId, BadgeConditionType.TOTAL_GRIDS, userBadgeRepository.countMyGrids(userId)));
		// 행정동 없는 격자(바다 등)는 region_stats 에 저장 행이 없어 empty → 수집률 판정 자체를 건너뛴다.
		userBadgeRepository.findMyRegionProgress(userId, gridId)
			.ifPresent(rate -> earned.addAll(award(userId, BadgeConditionType.REGION_PERCENT, rate)));
		return earned;
	}

	@Override
	@Transactional
	public List<EarnedBadgeResponseDto> award(long userId, BadgeConditionType type, BigDecimal metric) {
		List<EligibleBadgeProjection> candidates = badgeRepository.findEligible(type.name(), metric, userId);
		List<EarnedBadgeResponseDto> earned = new ArrayList<>(candidates.size());
		for (EligibleBadgeProjection candidate : candidates) {
			if (userBadgeRepository.insertIgnoreConflict(userId, candidate.getBadgeId()) == 1) {
				// 발급과 같은 커밋에 BADGE 알림 기록 (MSG-181 D1·D2) — 경합 패자(0행)는 record 도 안 탄다.
				notificationCommandService.record(userId, NotificationCategory.BADGE,
					"BADGE:" + candidate.getBadgeId(), "새 뱃지 획득",
					"'" + candidate.getName() + "' 뱃지를 획득했어요");
				earned.add(EarnedBadgeResponseDto.from(candidate));
			}
		}
		// 조기 return 없이 항상 통과한다 — 임박의 주 시나리오가 정확히 "지급 후보 0건 + 임박 후보 1건"이다.
		recordNearMiss(userId, type, metric);
		return earned;
	}

	/**
	 * 뱃지 임박 알림 기록 (docs/MSG-314.md). 생애 1회는 이벤트 키 유니크 + DO NOTHING(D2)이, 하루 1건은
	 * try 잠금 획득 후의 존재 확인(D3)이 강제한다 — 잠금을 못 잡으면 대기 대신 그 시도만 스킵한다
	 * (블로킹이면 업로드 트랜잭션의 streaks 행 잠금과 순서 사이클로 데드락, tryAcquireBadgeNearLock 주석).
	 * 잠금이 존재 확인보다 반드시 앞이다 — 뒤집히면 select-then-act 경합이 재발한다 (PR #114).
	 * 판정 축은 정수 개수 축 allowlist(D1) — REGION_PERCENT 는 소수 rate 라 "정확히 1 남음" 등식이
	 * 문구와 어긋나고, SPECIAL 은 수치 진행 축이 아니다. 대부분의 업로드는 findNearMiss 0건으로 잠금
	 * 없이 끝난다. 상한에 밀린 임박은 이월하지 않는다.
	 */
	private void recordNearMiss(long userId, BadgeConditionType type, BigDecimal metric) {
		if (!NEAR_MISS_TYPES.contains(type)) {
			return;
		}
		List<EligibleBadgeProjection> nearMisses = badgeRepository.findNearMiss(type.name(), metric, userId);
		if (nearMisses.isEmpty()) {
			return;
		}
		if (!Boolean.TRUE.equals(userBadgeRepository.tryAcquireBadgeNearLock(userId))) {
			return;
		}
		LocalDateTime todayStartUtc = LocalDate.ofInstant(clock.instant(), KST).atStartOfDay(KST)
			.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
		if (notificationRepository.countRecordedSince(userId, NEAR_EVENT_PREFIX, todayStartUtc) > 0) {
			return;
		}
		// 티어 값은 축 안에서 서로 다르므로 후보는 최대 1건이다 (D1).
		EligibleBadgeProjection nearMiss = nearMisses.get(0);
		notificationCommandService.record(userId, NotificationCategory.BADGE,
			NEAR_EVENT_PREFIX + nearMiss.getBadgeId(), "뱃지 획득 임박",
			"'" + nearMiss.getName() + "' 뱃지까지 딱 하나 남았어요");
	}
}

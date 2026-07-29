package com.msg.fillmap.badge.service;

import java.math.BigDecimal;
import java.util.List;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.entity.BadgeConditionType;

/**
 * 뱃지 판정·지급 엔진 (MSG-239 §D5). 축 무관 동형 — 호출부(훅)가 (type, metric)을 계산해 넘기면
 * 충족·미보유 티어 전부를 지급하고 신규 획득분을 반환한다(FR-2~4). B 내부 서비스 — 계약 인터페이스 아님.
 * 후속 티켓(MSG-200 스트릭·미션 엔진)의 배선은 "metric 계산 + award 호출 1줄 + 시딩 SQL"뿐이다(§D2).
 */
public interface BadgeAwardService {

	/**
	 * 호출 트랜잭션에 참여해 type 축에서 metric 으로 충족한 미보유 티어 전부를 지급한다.
	 *
	 * @return 이번 호출로 새로 획득한 뱃지 (없으면 빈 리스트 — 지배적 경로)
	 */
	List<EarnedBadgeResponseDto> award(long userId, BadgeConditionType type, BigDecimal metric);
}

package com.msg.fillmap.badge.service;

import java.math.BigDecimal;
import java.util.List;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.entity.BadgeConditionType;

/**
 * 뱃지 판정·지급 엔진 (MSG-239). B 내부 서비스 — 계약 인터페이스 아님.
 *
 * 역할 분담: 행동 단위 메서드(awardUploadBadges·awardCollectionBadges)는 metric 계산까지 뱃지
 * 도메인이 맡아, 호출하는 쪽(video 등)이 뱃지 repository 를 몰라도 되게 한다 — 타 도메인 접점은
 * Service 계층까지라는 3-Layer 원칙. 범용 award(type, metric)는 후속 티켓(MSG-200 스트릭·미션 판정
 * 엔진)이 자기 도메인에서 계산한 metric 을 직접 넘기는 진입점으로 유지한다 — 그쪽 배선은
 * "metric 계산 + award 호출 1줄 + 시딩 SQL"이 전부다.
 */
public interface BadgeAwardService {

	/**
	 * 업로드 행동의 뱃지 판정 — 생애 업로드 수(삭제 포함)를 세어 업로드 수 축을 판정한다.
	 * 모든 업로드(재방문 포함)에서 호출한다.
	 *
	 * @return 이번 호출로 새로 획득한 뱃지 (없으면 빈 리스트 — 지배적 경로)
	 */
	List<EarnedBadgeResponseDto> awardUploadBadges(long userId);

	/**
	 * 첫 수집 행동의 뱃지 판정 — 수집 격자 수 축과, 그 격자 귀속 행정동의 수집률 축을 판정해 합산
	 * 반환한다. 수집률은 region_stats 에 저장된 값을 읽기만 하므로 **호출 전에 refresh 로 그 값이
	 * 갱신돼 있어야 하고**, 행정동 없는 격자(바다 등)는 저장 행이 없어 수집률 판정을 건너뛴다.
	 *
	 * @return 이번 호출로 새로 획득한 뱃지 (없으면 빈 리스트)
	 */
	List<EarnedBadgeResponseDto> awardCollectionBadges(long userId, String gridId);

	/**
	 * 범용 진입점 — 호출자가 계산한 metric 으로 type 축에서 충족·미보유 티어 전부를 지급한다.
	 * 호출 트랜잭션에 참여한다.
	 *
	 * @return 이번 호출로 새로 획득한 뱃지 (없으면 빈 리스트)
	 */
	List<EarnedBadgeResponseDto> award(long userId, BadgeConditionType type, BigDecimal metric);
}

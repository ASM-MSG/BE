package com.msg.fillmap.route.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 경로 추천 보행 경로 설정 (MSG-483). RouteAiProperties 선례의 record 바인딩 —
 * @ConfigurationPropertiesScan 이 자동 등록한다. route.ai 와 독립 게이트다 — 추천만 켜고 보행 경로는
 * 끌 수 있다.
 *
 * enabled 기본 false (NFR-OPS-06 관례) — 꺼진 환경에서 TmapWalkClient 빈이 뜨지 않고 호출은 14504
 * 명시적 비활성 응답이다. dailyLimit 은 TMap 무료 한도(경로안내 그룹 합산 일 1,000건)의 보수 계획
 * 기본 900 이다. timeout 은 TMap 호출당 교환 전체 시한이다 (카카오 NFR-PERF-07 선례와 같은 급).
 */
@ConfigurationProperties(prefix = "route.walk")
public record RouteWalkProperties(
	@DefaultValue("false") boolean enabled,
	@DefaultValue("https://apis.openapi.sk.com") String baseUrl,
	@DefaultValue("") String appKey,
	@DefaultValue("900") int dailyLimit,
	@DefaultValue("PT3S") Duration timeout
) {

	public RouteWalkProperties {
		// 켜졌는데 키가 비면 모든 요청이 TMap 인증 실패로 조용히 직선 폴백돼 폴백 설계가 설정 장애를
		// 감춘다 (NFR-SEC-10). 설정 오류는 런타임이 아니라 바인딩 시점에 잡는다 — prod 전용 검증기
		// 대신 전 프로파일 검증인 이유는 이 실수가 플래그를 먼저 켜 보는 dev 에서 먼저 나기 때문이다.
		if (enabled && (appKey == null || appKey.isBlank())) {
			throw new IllegalStateException("route.walk.enabled 인데 route.walk.app-key 가 비어 있습니다");
		}
	}
}

package com.msg.fillmap.route.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 경로 추천 서버 연동 설정 (MSG-457). AiProperties 관례의 record 바인딩 — @ConfigurationPropertiesScan 이
 * 자동 등록한다. video 쪽 ai.* 설정과 별개 키다 — 블러가 꺼진 환경에서도 경로 추천은 독립으로 켠다.
 *
 * enabled 기본 false (NFR-OPS-06 관례) — 꺼진 환경에서 RouteIntentClient 빈이 뜨지 않고 호출은 14503
 * 명시적 비활성 응답이다. timeout 은 AI 호출당(parse·explain 각각) 교환 전체 시한이다 — 실측 근거는
 * 스펙 "응답 시간 상한 확정" 절 (관측 최악 1.6초의 3배 여유).
 */
@ConfigurationProperties(prefix = "route.ai")
public record RouteAiProperties(
	@DefaultValue("false") boolean enabled,
	@DefaultValue("http://localhost:8000") String baseUrl,
	@DefaultValue("PT5S") Duration timeout
) {
}

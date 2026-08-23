package com.msg.fillmap.video.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI Highlight-Blur 서버 연동 설정 (MSG-149). AwsProperties 관례를 따른 record 바인딩.
 *
 * enabled 기본 false — AI 서버가 없는 로컬/CI/현재 prod 에서는 인코딩이 오늘처럼 READY 로 끝나고
 * 폴러 빈(bean)도 뜨지 않는다(@ConditionalOnProperty). base-url 은 enabled 일 때만 의미가 있어 필수가 아니다.
 *
 * blurEnabled 는 블러 파이프라인 전용 게이트 (MSG-456). 실효 블러 활성 = enabled && blurEnabled —
 * 블러는 AiClient·RestClient.Builder(enabled 게이트)에 의존하므로 단독으로는 켤 수 없다.
 * 기본 false 라 배포만으로 블러가 꺼지고(FR-4), 꺼진 동안에도 선분석·재생 하이라이트는 enabled 만 따른다.
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
	@DefaultValue("false") boolean enabled,
	@DefaultValue("false") boolean blurEnabled,
	String baseUrl,
	@DefaultValue("PT30M") Duration timeout,
	@DefaultValue("30000") long pollIntervalMs
) {
}

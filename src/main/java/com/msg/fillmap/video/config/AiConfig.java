package com.msg.fillmap.video.config;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * AI 폴러 스케줄링 + AiClient 용 RestClient.Builder 설정 (MSG-149). ai.enabled 일 때만 @EnableScheduling 과
 * 이 빈들이 뜬다 — 비활성 환경(로컬/CI/현재 prod)에서는 폴러 빈(bean)도 스케줄러도 없어 실 DB 를 건드리거나
 * 테스트를 오염시키지 않는다. RestClient.Builder 를 여기서 직접 제공한다 — Spring Boot 4.1 에는 RestClient
 * 자동설정 모듈이 없어 주입 가능한 RestClient.Builder 빈이 없기 때문이다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "ai", name = "enabled")
public class AiConfig {

	// AI 서버가 TCP 는 받되 응답을 안 주는 hang 이면 단일 스레드 @Scheduled 폴러 전체가 정지한다.
	// connect/read 타임아웃으로 hang 을 예외로 만들어 잡별 catch → 다음 주기 재시도로 수렴시킨다.
	// read 는 소켓 read 단위 정지 감지라 블러본(수십 MB, 같은 리전) 다운로드의 총 소요와 무관 — 넉넉히 둔다.
	// env 별 튜닝 수요가 없어 프로퍼티가 아니라 상수로 둔다(YAGNI). 필요해지면 AiProperties 로 승격한다.
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

	@Bean
	RestClient.Builder aiRestClientBuilder() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(CONNECT_TIMEOUT);
		factory.setReadTimeout(READ_TIMEOUT);
		return RestClient.builder().requestFactory(factory);
	}
}

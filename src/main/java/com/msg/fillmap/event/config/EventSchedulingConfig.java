package com.msg.fillmap.event.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 행사 도메인 스케줄링 인프라 (MSG-442). **조건이 없는 것이 이 클래스의 요지다.**
 * <p>
 * 레포의 다른 {@code @EnableScheduling} 두 곳은 둘 다 조건부다 — AiConfig 는 {@code ai.enabled},
 * NotificationConfig 는 {@code fillmap.notification.enabled} 게이트 안에 있다. 두 플래그가 모두 꺼진
 * 환경(기본값, local·CI 가 이 상태)에서는 스케줄링 인프라 자체가 뜨지 않아 {@code @Scheduled} 가 아예
 * 돌지 않는다. 그러면 구독 API 는 열려 있는데 종료 구독 해제(FR-16)가 영원히 돌지 않아 행이 무한
 * 축적된다. 정리는 발송 게이트 밖이어야 한다.
 * <p>
 * {@code @EnableScheduling} 중복 선언은 무해하다 — 스프링이 인프라 빈을 한 번만 등록하는 멱등 애너테이션이다.
 */
@Configuration
@EnableScheduling
public class EventSchedulingConfig {
}

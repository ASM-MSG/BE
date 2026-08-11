package com.msg.fillmap.video.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;

import com.msg.fillmap.video.service.AiBlurPoller;
import com.msg.fillmap.video.service.AiClient;

/**
 * ai.enabled=true 로 컨텍스트를 띄우는 유일한 테스트 (회귀 방어). 이게 없어서 "AI 게이트 빈이 실제로 wiring
 * 되는가"가 검증 안 됐고, RestClient.Builder 빈 부재(Spring Boot 4.1 에 RestClient 자동설정 없음)로 AiClient
 * 가 프로덕션에서 못 뜨는 잠복 버그가 마스킹됐다. 컨텍스트 로드 자체가 wiring 단언이며, 빈 존재를 명시 확인한다.
 *
 * 폴러 @Scheduled 는 기동 직후 1회 돌지만 BLURRING 영상이 없어 no-op 다 — AI 서버 연결도 시도하지 않는다.
 */
// 검증: FR-MEDIA-05
@SpringBootTest(properties = "ai.enabled=true")
@DisplayName("AI 활성 컨텍스트 스모크")
class AiEnabledContextTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void AI_활성_시_컨텍스트가_뜨고_AI_빈들이_등록된다() {
		assertThat(context.getBean(AiClient.class)).isNotNull();
		assertThat(context.getBean(AiBlurPoller.class)).isNotNull();
		assertThat(context.getBean(RestClient.Builder.class)).isNotNull();
	}
}

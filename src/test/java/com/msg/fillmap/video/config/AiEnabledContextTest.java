package com.msg.fillmap.video.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;

import com.msg.fillmap.video.service.AiBlurPoller;
import com.msg.fillmap.video.service.AiClient;

/**
 * AI 플래그 조합별 빈 유무 (MSG-149 회귀 방어 + MSG-456 블러 전용 플래그 분리). 컨텍스트 로드 자체가
 * wiring 단언이며, 빈 존재/부재를 명시 확인한다 — RestClient.Builder 빈 부재(Spring Boot 4.1 에 RestClient
 * 자동설정 없음)로 AiClient 가 프로덕션에서 못 뜨는 잠복 버그가 이 테스트 없이는 마스킹됐다.
 *
 * 실효 블러 활성 = ai.enabled && ai.blur-enabled — 폴러는 둘 다 켜져야 뜨고(FR-3), 선분석·후행 하이라이트
 * 계산이 쓰는 AiClient 는 ai.enabled 만 따른다(FR-1). 폴러가 뜨는 조합에서 @Scheduled 는 기동 직후 1회
 * 돌지만 BLURRING 영상이 없어 no-op 다 — AI 서버 연결도 시도하지 않는다.
 */
@DisplayName("AI 플래그 조합별 컨텍스트 스모크")
class AiEnabledContextTest {

	// 검증: FR-MEDIA-18 (블러만 꺼진 배포 후 dev — 선분석은 살고 블러 폴러는 없다)
	@Nested
	@SpringBootTest(properties = {"ai.enabled=true", "ai.blur-enabled=false"})
	@DisplayName("ai.enabled=true / ai.blur-enabled=false")
	class 블러만_꺼진_컨텍스트 {

		@Autowired
		private ApplicationContext context;

		@Test
		void AI만_켜면_선분석_빈은_뜨고_블러_폴러는_뜨지_않는다() {
			assertThat(context.getBean(AiClient.class)).isNotNull();
			assertThat(context.getBean(RestClient.Builder.class)).isNotNull();
			assertThat(context.getBeansOfType(AiBlurPoller.class)).isEmpty();
		}
	}

	// 검증: FR-MEDIA-05
	@Nested
	@SpringBootTest(properties = {"ai.enabled=true", "ai.blur-enabled=true"})
	@DisplayName("ai.enabled=true / ai.blur-enabled=true")
	class 블러_재활성_컨텍스트 {

		@Autowired
		private ApplicationContext context;

		@Test
		void AI와_블러를_모두_켜면_폴러까지_뜬다() {
			assertThat(context.getBean(AiClient.class)).isNotNull();
			assertThat(context.getBean(RestClient.Builder.class)).isNotNull();
			assertThat(context.getBean(AiBlurPoller.class)).isNotNull();
		}
	}

	// 검증: FR-MEDIA-18 (방어 — 블러 단독 플래그로는 아무것도 켜지지 않는다)
	@Nested
	@SpringBootTest(properties = {"ai.enabled=false", "ai.blur-enabled=true"})
	@DisplayName("ai.enabled=false / ai.blur-enabled=true")
	class AI_꺼짐_컨텍스트 {

		@Autowired
		private ApplicationContext context;

		@Test
		void AI를_끄면_블러_설정과_무관하게_AI_빈이_없다() {
			assertThat(context.getBeansOfType(AiClient.class)).isEmpty();
			assertThat(context.getBeansOfType(AiBlurPoller.class)).isEmpty();
			assertThat(context.getBeansOfType(RestClient.Builder.class)).isEmpty();
		}
	}
}

package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.route.config.RouteAiProperties;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto;
import com.msg.fillmap.route.dto.RouteRecommendRequestDto.ViewportDto;
import com.msg.fillmap.route.exception.RouteErrorCode;

/**
 * 뷰포트 사전 검증 (MSG-457 §API) — AI 의 Viewport 검증과 같은 규칙을 parse 호출 전에 BE 가 적용한다.
 * 통과 못 한 요청이 AI 까지 가서 422 로 돌아오면 의미가 다른 14502 로 새기 때문에, "AI 호출 전"이
 * 검증의 본질이다. 실제 RouteIntentClient 를 MockRestServiceServer(기대 0회)에 물려 그 성질을 고정한다.
 */
@DisplayName("RouteRecommendService — 뷰포트 사전 검증")
class RouteRecommendServiceTest {

	private static final long USER_ID = 42L;

	private MockRestServiceServer server;
	private RouteRecommendService service;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		RouteIntentClient intentClient = new RouteIntentClient(builder,
			new RouteAiProperties(true, "https://route-ai.test", Duration.ofSeconds(5)));

		@SuppressWarnings("unchecked")
		ObjectProvider<RouteIntentClient> provider = mock(ObjectProvider.class);
		given(provider.getIfAvailable()).willReturn(intentClient);
		service = new RouteRecommendServiceImpl(provider);
	}

	// 검증: FR-ROUTE-01, NFR-SEC-08
	@Test
	@DisplayName("잘못된 좌표는 AI 호출 전에 걸러진다 — 전 케이스에서 parse 미호출 (기대 0회)")
	void 잘못된_좌표는_AI_호출_전에_걸러진다() {
		뷰포트가_거부된다(new ViewportDto(35.1, 128.95, 35.1, 129.20), RouteErrorCode.INVALID_VIEWPORT); // 넓이 0
		뷰포트가_거부된다(new ViewportDto(35.05, 128.95, 91.0, 129.20), RouteErrorCode.INVALID_VIEWPORT); // 위도 91
		뷰포트가_거부된다(new ViewportDto(Double.NaN, 128.95, 35.25, 129.20), RouteErrorCode.INVALID_VIEWPORT); // NaN
		뷰포트가_거부된다(new ViewportDto(35.25, 128.95, 35.05, 129.20), RouteErrorCode.INVALID_VIEWPORT); // 뒤집힘
		뷰포트가_거부된다(new ViewportDto(35.05, 128.95, 35.25, 129.50), RouteErrorCode.VIEWPORT_TOO_LARGE); // 0.55도

		server.verify(); // 기대를 하나도 걸지 않았다 — 요청이 한 번이라도 나갔다면 그 시점에 이미 실패했다
	}

	private void 뷰포트가_거부된다(ViewportDto viewport, RouteErrorCode expected) {
		RouteRecommendRequestDto request = new RouteRecommendRequestDto("해운대 가자", viewport, null);
		assertThatThrownBy(() -> service.recommend(USER_ID, request))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", expected);
	}
}

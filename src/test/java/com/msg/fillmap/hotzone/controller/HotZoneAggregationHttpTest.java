package com.msg.fillmap.hotzone.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 핫구역 행정 단위 집계 HTTP 계약 (실 서비스 빈 체인 · MSG-466 §API 명세). 검증 우선순위
 * (bbox 누락 8400 → unit 8405 → 정의역·NaN 8400 → 뒤집힘 8400 → 단위별 span 8401)와 사용자 무관 응답,
 * 비로그인 개방을 본다. 200 케이스의 bbox 는 서해 공해상이라 공유 Redis 에 남은 핫 격자와 무관하게 빈 배열이다
 * (뷰포트 필터에서 전부 빠지므로 DB 조회도 타지 않는다). 집계 산술은 HotZoneAggregateServiceImplTest 담당.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("핫구역 행정 단위 집계 HTTP 검증 (실 서비스)")
class HotZoneAggregationHttpTest {

	private static final long USER_ID = 7466L;
	private static final long OTHER_USER_ID = 7467L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	private String bearer(long userId) {
		return "Bearer " + tokenProvider.issueAccessToken(userId, UserRole.USER);
	}

	private MockHttpServletRequestBuilder aggregation(String unit,
		String swLat, String swLng, String neLat, String neLng) {
		MockHttpServletRequestBuilder request = get("/api/hotzones/aggregation")
			.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID));
		if (unit != null) {
			request.param("unit", unit);
		}
		if (swLat != null) {
			request.param("swLat", swLat).param("swLng", swLng).param("neLat", neLat).param("neLng", neLng);
		}
		return request;
	}

	/** 서해 공해상의 유효한 소형 bbox — 핫 격자가 없어 빈 배열 200 이다. */
	private MockHttpServletRequestBuilder aggregation(String unit) {
		return aggregation(unit, "36.30", "124.30", "36.50", "124.50");
	}

	@Nested
	@DisplayName("파라미터 검증")
	class 파라미터_검증 {

		@Test
		@DisplayName("bbox가 누락되면 8400이다")
		void bbox가_누락되면_8400이다() throws Exception {
			mockMvc.perform(aggregation("DONG", null, null, null, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(8400));
		}

		@Test
		@DisplayName("bbox와 unit이 동시에 잘못되면 8400이 우선한다")
		void bbox와_unit이_동시에_잘못되면_8400이_우선한다() throws Exception {
			// 검증 순서가 흔들리면 같은 요청이 8400 과 8405 를 오간다 (§API 명세 검증 우선순위).
			mockMvc.perform(aggregation("GU", null, null, null, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(8400));
		}

		@Test
		@DisplayName("좌표가 NaN이면 8400이다")
		void 좌표가_NaN이면_8400이다() throws Exception {
			mockMvc.perform(aggregation("DONG", "NaN", "124.30", "36.50", "124.50"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(8400));
		}

		@Test
		@DisplayName("좌표가 WGS84 정의역 밖이면 8400이다")
		void 좌표가_WGS84_정의역_밖이면_8400이다() throws Exception {
			mockMvc.perform(aggregation("DONG", "100.0", "124.30", "100.5", "124.50"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(8400));
		}

		@Test
		@DisplayName("뒤집힌 bbox는 8400이다")
		void 뒤집힌_bbox는_8400이다() throws Exception {
			mockMvc.perform(aggregation("DONG", "36.50", "124.50", "36.30", "124.30"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(8400));
		}

		@Test
		@DisplayName("unit이 없거나 미지원 값이면 8405다")
		void unit이_없거나_미지원_값이면_8405다() throws Exception {
			mockMvc.perform(aggregation(null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(8405));
			mockMvc.perform(aggregation("GU"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(8405));
		}

		@Test
		@DisplayName("unit은 대소문자 무관이다")
		void unit은_대소문자_무관이다() throws Exception {
			mockMvc.perform(aggregation("sigungu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));
		}

		@Test
		@DisplayName("범위 안에 핫 격자가 없으면 빈 배열 200이다")
		void 범위_안에_핫_격자가_없으면_빈_배열_200이다() throws Exception {
			mockMvc.perform(aggregation("DONG"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data.length()").value(0));
		}

		@Test
		@DisplayName("개별 조회는 종전대로 span 상한 없이 성립한다")
		void 개별_조회는_종전대로_span_상한_없이_성립한다() throws Exception {
			// 집계에 상한이 붙었다고 기존 개별 조회 계약이 좁아지면 안 된다 (MSG-466 D5, PRD 비목표).
			mockMvc.perform(get("/api/hotzones")
					.param("swLat", "30.00").param("swLng", "120.00")
					.param("neLat", "40.00").param("neLng", "132.00")
					.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));
		}
	}

	@Nested
	@DisplayName("단위별 bbox 상한")
	class 단위별_bbox_상한 {

		@Test
		@DisplayName("동 단위 bbox 한 변이 1도를 넘으면 8401이다")
		void 동_단위_bbox_한_변이_1도를_넘으면_8401이다() throws Exception {
			mockMvc.perform(aggregation("DONG", "35.00", "124.00", "36.01", "124.50"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(8401));
		}

		@Test
		@DisplayName("구 단위 bbox 한 변이 4도를 넘으면 8401이다")
		void 구_단위_bbox_한_변이_4도를_넘으면_8401이다() throws Exception {
			mockMvc.perform(aggregation("SIGUNGU", "33.00", "124.00", "37.01", "124.50"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(8401));
		}

		@Test
		@DisplayName("시 단위 bbox 한 변이 10도를 넘으면 8401이다")
		void 시_단위_bbox_한_변이_10도를_넘으면_8401이다() throws Exception {
			mockMvc.perform(aggregation("SIDO", "20.00", "124.00", "30.01", "124.50"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(8401));
		}

		@Test
		@DisplayName("동 단위 bbox 한 변이 정확히 1도면 허용된다")
		void 동_단위_bbox_한_변이_정확히_1도면_허용된다() throws Exception {
			mockMvc.perform(aggregation("DONG", "35.50", "124.00", "36.50", "125.00"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));
		}

		@Test
		@DisplayName("시 단위는 전국 뷰포트를 허용한다")
		void 시_단위는_전국_뷰포트를_허용한다() throws Exception {
			mockMvc.perform(aggregation("SIDO", "33.00", "124.00", "39.00", "132.00"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));
		}
	}

	@Nested
	@DisplayName("사용자 무관 계약")
	class 사용자_무관_계약 {

		// 검증: FR-HOTZONE-13
		@Test
		@DisplayName("컨트롤러가 principal을 받지 않는다 — 응답이 사용자와 무관하다는 계약의 코드 수준 방어")
		void 컨트롤러가_principal을_받지_않는다() {
			Method endpoint = Arrays.stream(HotZoneController.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("getHotZoneAggregates"))
				.findFirst()
				.orElseThrow();

			assertThat(endpoint.getParameters())
				.as("principal 을 받는 순간 사용자별 응답을 만들 여지가 생기고 전역 캐시 전제가 깨진다")
				.noneMatch(parameter -> parameter.isAnnotationPresent(AuthenticationPrincipal.class));
			assertThat(Arrays.stream(endpoint.getParameters()).map(Parameter::getType))
				.allMatch(type -> type == String.class || type == Double.class);
		}

		// 검증: FR-HOTZONE-13
		@Test
		@DisplayName("서로 다른 두 사용자의 같은 파라미터 요청은 같은 응답을 받는다")
		void 서로_다른_두_사용자의_같은_파라미터_요청은_같은_응답을_받는다() throws Exception {
			String first = mockMvc.perform(aggregation("SIGUNGU"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			String second = mockMvc.perform(get("/api/hotzones/aggregation")
					.param("unit", "SIGUNGU")
					.param("swLat", "36.30").param("swLng", "124.30")
					.param("neLat", "36.50").param("neLng", "124.50")
					.header(HttpHeaders.AUTHORIZATION, bearer(OTHER_USER_ID)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

			assertThat(second).isEqualTo(first);
		}

		// 검증: FR-HOTZONE-13
		@Test
		@DisplayName("비로그인 요청도 집계를 받는다")
		void 비로그인_요청도_집계를_받는다() throws Exception {
			// 토큰 없이도 200 이다 (GET permitAll, MSG-454 상단 칩 원칙).
			mockMvc.perform(get("/api/hotzones/aggregation")
					.param("unit", "SIGUNGU")
					.param("swLat", "36.30").param("swLng", "124.30")
					.param("neLat", "36.50").param("neLng", "124.50"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));
		}
	}
}

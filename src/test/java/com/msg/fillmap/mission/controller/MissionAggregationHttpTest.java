package com.msg.fillmap.mission.controller;

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
 * 미션 행정 단위 집계 HTTP 계약 (실 서비스 빈 체인 · MSG-437 §API 명세 · Owner B). 검증 우선순위
 * (bbox 누락 12400 → type 12402 → unit 12405 → 정의역·NaN 12400 → 뒤집힘 12400 → 단위별 span 12401)와
 * 사용자 무관 응답을 본다. 검증은 대부분 스냅숏 재계산 앞에서 끝나 합성 데이터가 필요 없고, 200 케이스는
 * 서해 공해상 bbox 라 공유 로컬 DB 의 실데이터와 무관하게 빈 배열이다. 집계 산술은
 * MissionAggregationIntegrationTest 담당.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("미션 행정 단위 집계 HTTP 검증 (실 서비스)")
class MissionAggregationHttpTest {

	private static final long USER_ID = 7437L;
	private static final long OTHER_USER_ID = 7438L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	private String bearer(long userId) {
		return "Bearer " + tokenProvider.issueAccessToken(userId, UserRole.USER);
	}

	/** 서해 공해상의 유효한 소형 bbox — 미션이 없어 빈 배열 200 이다. */
	private MockHttpServletRequestBuilder aggregation(String type, String unit,
		String swLat, String swLng, String neLat, String neLng) {
		MockHttpServletRequestBuilder request = get("/api/missions/aggregation")
			.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID));
		if (type != null) {
			request.param("type", type);
		}
		if (unit != null) {
			request.param("unit", unit);
		}
		if (swLat != null) {
			request.param("swLat", swLat).param("swLng", swLng).param("neLat", neLat).param("neLng", neLng);
		}
		return request;
	}

	private MockHttpServletRequestBuilder aggregation(String type, String unit) {
		return aggregation(type, unit, "36.30", "124.30", "36.50", "124.50");
	}

	@Nested
	@DisplayName("파라미터 검증")
	class 파라미터_검증 {

		@Test
		@DisplayName("bbox가 누락되면 12400이다")
		void bbox가_누락되면_12400이다() throws Exception {
			mockMvc.perform(aggregation("EVENT", "DONG", null, null, null, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12400));
		}

		@Test
		@DisplayName("bbox와 unit이 동시에 잘못되면 12400이 우선한다")
		void bbox와_unit이_동시에_잘못되면_12400이_우선한다() throws Exception {
			// 검증 순서가 흔들리면 같은 요청이 12400 과 12405 를 오간다 (§API 명세 검증 우선순위).
			mockMvc.perform(aggregation("EVENT", "GU", null, null, null, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12400));
		}

		@Test
		@DisplayName("type과 unit이 동시에 잘못되면 12402가 우선한다")
		void type과_unit이_동시에_잘못되면_12402가_우선한다() throws Exception {
			// bbox 누락 → type → unit 3단 우선순위의 가운데 전이 — bbox 는 유효하고 뒤 둘만 틀린 요청이다.
			mockMvc.perform(aggregation("COURSE", "GU"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12402));
		}

		@Test
		@DisplayName("좌표가 NaN이면 12400이다")
		void 좌표가_NaN이면_12400이다() throws Exception {
			mockMvc.perform(aggregation("EVENT", "DONG", "NaN", "124.30", "36.50", "124.50"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12400));
		}

		@Test
		@DisplayName("좌표가 WGS84 정의역 밖이면 12400이다")
		void 좌표가_WGS84_정의역_밖이면_12400이다() throws Exception {
			mockMvc.perform(aggregation("EVENT", "DONG", "100.0", "124.30", "100.5", "124.50"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12400));
		}

		@Test
		@DisplayName("뒤집힌 bbox는 12400이다")
		void 뒤집힌_bbox는_12400이다() throws Exception {
			mockMvc.perform(aggregation("EVENT", "DONG", "36.50", "124.50", "36.30", "124.30"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12400));
		}

		@Test
		@DisplayName("코스 type으로 집계를 요청하면 12402다")
		void 코스_type으로_집계를_요청하면_12402다() throws Exception {
			// 코스는 1km 화면 묶음을 유지한다 — 행정 집계 대상이 아니다 (MSG-437 §개요).
			mockMvc.perform(aggregation("COURSE", "DONG"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12402));
		}

		@Test
		@DisplayName("unit이 없거나 미지원 값이면 12405다")
		void unit이_없거나_미지원_값이면_12405다() throws Exception {
			mockMvc.perform(aggregation("EVENT", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12405));
			mockMvc.perform(aggregation("EVENT", "GU"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12405));
		}

		@Test
		@DisplayName("type과 unit은 대소문자 무관이다")
		void type과_unit은_대소문자_무관이다() throws Exception {
			mockMvc.perform(aggregation("popup", "sigungu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));
		}

		@Test
		@DisplayName("범위 안에 미션이 없으면 빈 배열 200이다")
		void 범위_안에_미션이_없으면_빈_배열_200이다() throws Exception {
			mockMvc.perform(aggregation("EVENT", "DONG"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data.length()").value(0));
		}
	}

	@Nested
	@DisplayName("단위별 bbox 상한")
	class 단위별_bbox_상한 {

		@Test
		@DisplayName("동 단위 bbox 한 변이 1도를 넘으면 12401이다")
		void 동_단위_bbox_한_변이_1도를_넘으면_12401이다() throws Exception {
			mockMvc.perform(aggregation("EVENT", "DONG", "35.00", "124.00", "36.01", "124.50"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12401));
		}

		@Test
		@DisplayName("구 단위 bbox 한 변이 4도를 넘으면 12401이다")
		void 구_단위_bbox_한_변이_4도를_넘으면_12401이다() throws Exception {
			mockMvc.perform(aggregation("EVENT", "SIGUNGU", "33.00", "124.00", "37.01", "124.50"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12401));
		}

		@Test
		@DisplayName("시 단위 bbox 한 변이 10도를 넘으면 12401이다")
		void 시_단위_bbox_한_변이_10도를_넘으면_12401이다() throws Exception {
			mockMvc.perform(aggregation("EVENT", "SIDO", "20.00", "124.00", "30.01", "124.50"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12401));
		}

		@Test
		@DisplayName("동 단위 bbox 한 변이 정확히 1도면 허용된다")
		void 동_단위_bbox_한_변이_정확히_1도면_허용된다() throws Exception {
			mockMvc.perform(aggregation("EVENT", "DONG", "35.50", "124.00", "36.50", "125.00"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));
		}

		@Test
		@DisplayName("시 단위는 한 변 10도까지 허용된다 — 개별 조회 0.5도로는 못 여는 넓은 화면")
		void 시_단위는_한_변_10도까지_허용된다() throws Exception {
			// FR-29: 시 축척에서는 개별 목록 조회(0.5도 상한)가 아예 성립하지 않는다.
			mockMvc.perform(aggregation("EVENT", "SIDO", "30.00", "120.00", "40.00", "130.00"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));
		}
	}

	@Nested
	@DisplayName("사용자 무관 계약")
	class 사용자_무관_계약 {

		// 검증: FR-MISSION-20
		@Test
		@DisplayName("컨트롤러가 principal을 받지 않는다 — 응답이 사용자와 무관하다는 계약의 코드 수준 방어")
		void 컨트롤러가_principal을_받지_않는다() {
			Method endpoint = Arrays.stream(MissionController.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("getMissionAggregates"))
				.findFirst()
				.orElseThrow();

			assertThat(endpoint.getParameters())
				.as("principal 을 받는 순간 사용자별 응답을 만들 여지가 생기고 전역 캐시 전제가 깨진다")
				.noneMatch(parameter -> parameter.isAnnotationPresent(AuthenticationPrincipal.class));
			assertThat(Arrays.stream(endpoint.getParameters()).map(Parameter::getType))
				.allMatch(type -> type == String.class || type == Double.class);
		}

		// 검증: FR-MISSION-02
		@Test
		@DisplayName("서로 다른 두 사용자의 같은 파라미터 요청은 같은 응답을 받는다")
		void 서로_다른_두_사용자의_같은_파라미터_요청은_같은_응답을_받는다() throws Exception {
			String first = mockMvc.perform(get("/api/missions/aggregation")
					.param("type", "POPUP").param("unit", "SIGUNGU")
					.param("swLat", "35.00").param("swLng", "128.50")
					.param("neLat", "35.50").param("neLng", "129.50")
					.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			String second = mockMvc.perform(get("/api/missions/aggregation")
					.param("type", "POPUP").param("unit", "SIGUNGU")
					.param("swLat", "35.00").param("swLng", "128.50")
					.param("neLat", "35.50").param("neLng", "129.50")
					.header(HttpHeaders.AUTHORIZATION, bearer(OTHER_USER_ID)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

			assertThat(second).isEqualTo(first);
		}
	}
}

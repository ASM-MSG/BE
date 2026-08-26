package com.msg.fillmap.video.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.dto.ExploreGridResponseDto;
import com.msg.fillmap.video.dto.ExploreSort;
import com.msg.fillmap.video.dto.RegionExploreResponseDto;
import com.msg.fillmap.video.dto.RegionGridCountResponseDto;
import com.msg.fillmap.video.service.RegionExplorePage;
import com.msg.fillmap.video.service.RegionExploreService;

/**
 * 전역 탐색 API HTTP 계약 (MSG-238 모듈 4). 게이트·정렬·카운트는 RegionExploreQueryTest(실 DB),
 * limit 보정·presign 호출·미존재 합성은 RegionExploreServiceTest 가 검증하고, 여기서는 sort defaultValue
 * 바인딩·무효 sort 400(대문자 전용 계약 — 소문자 포함, §D3)·presigned URL 통과·미존재 200·미인증 401 을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("RegionExploreController")
class RegionExploreControllerTest {

	private static final long USER_ID = 4238L;
	private static final String REGION_CODE = "2644056000";
	private static final String GRIDS_URL = "/api/regions/{regionCode}/grids";
	private static final String EXPLORE_URL = "/api/regions/explore";
	private static final String SIGNED_URL =
		"https://bucket.s3/thumb.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=600&X-Amz-Signature=abc";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private RegionExploreService regionExploreService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	private RegionExploreResponseDto responseWithCard(String coverThumbnailUrl) {
		return new RegionExploreResponseDto(REGION_CODE, "부산광역시 부산진구 부전2동", 5, 355L,
			List.of(new ExploreGridResponseDto("16676_11596", 16676L, 11596L, 138,
				coverThumbnailUrl, (short) 12, "서면", "F-6")));
	}

	@Test
	@DisplayName("커버_썸네일은_presigned_GET_URL로_발급된다")
	void 커버_썸네일은_presigned_GET_URL로_발급된다() throws Exception {
		// presign 자체(ThumbnailUrlPresigner 재사용·null 통과)는 서비스 테스트가 검증 — 여기서는 서명
		// 파라미터를 가진 URL 이 카드 필드로 그대로 내려가는 HTTP 계약을 본다(87/167 컨트롤러 테스트 관례).
		given(regionExploreService.getRegionGrids(eq(REGION_CODE), eq(ExploreSort.POPULAR), isNull()))
			.willReturn(responseWithCard(SIGNED_URL));

		mockMvc.perform(get(GRIDS_URL, REGION_CODE)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.regionCode").value(REGION_CODE))
			.andExpect(jsonPath("$.data.regionName").value("부산광역시 부산진구 부전2동"))
			.andExpect(jsonPath("$.data.gridCount").value(5))
			.andExpect(jsonPath("$.data.videoCount").value(355))
			.andExpect(jsonPath("$.data.grids[0].gridId").value("16676_11596"))
			.andExpect(jsonPath("$.data.grids[0].gridY").value(16676))
			.andExpect(jsonPath("$.data.grids[0].gridX").value(11596))
			.andExpect(jsonPath("$.data.grids[0].videoCount").value(138))
			.andExpect(jsonPath("$.data.grids[0].coverDurationSec").value(12))
			.andExpect(jsonPath("$.data.grids[0].coverThumbnailUrl", containsString("X-Amz-Algorithm")))
			.andExpect(jsonPath("$.data.grids[0].coverThumbnailUrl", containsString("X-Amz-Signature")))
			.andExpect(jsonPath("$.data.grids[0].coverThumbnailUrl", containsString("X-Amz-Expires")))
			.andExpect(jsonPath("$.data.grids[0].zoneName").value("서면"))
			.andExpect(jsonPath("$.data.grids[0].zoneCell").value("F-6"));
	}

	@Test
	@DisplayName("sort를_생략하면_인기순이다")
	void sort를_생략하면_인기순이다() throws Exception {
		given(regionExploreService.getRegionGrids(eq(REGION_CODE), eq(ExploreSort.POPULAR), isNull()))
			.willReturn(responseWithCard(null));

		mockMvc.perform(get(GRIDS_URL, REGION_CODE)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk());

		// defaultValue = "POPULAR" 바인딩 — sort 무지정 요청이 인기순으로 서비스에 도달한다(§D3).
		then(regionExploreService).should().getRegionGrids(REGION_CODE, ExploreSort.POPULAR, null);
	}

	@Test
	@DisplayName("sort와_limit_파라미터는_서비스에_그대로_전달된다")
	void sort와_limit_파라미터는_서비스에_그대로_전달된다() throws Exception {
		given(regionExploreService.getRegionGrids(eq(REGION_CODE), eq(ExploreSort.LATEST), eq(3)))
			.willReturn(responseWithCard(null));

		mockMvc.perform(get(GRIDS_URL, REGION_CODE)
				.queryParam("sort", "LATEST")
				.queryParam("limit", "3")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk());

		// 컨트롤러는 파싱·전달만(3-layer 얇게) — limit 1미만 보정은 서비스 몫(RegionExploreServiceTest, §D2).
		then(regionExploreService).should().getRegionGrids(REGION_CODE, ExploreSort.LATEST, 3);
	}

	@Test
	@DisplayName("유효하지_않은_sort는_400이다")
	void 유효하지_않은_sort는_400이다() throws Exception {
		// 대문자 전용 계약(§D3) — 소문자 popular 포함 무효 값 전부 enum 바인딩 실패 → 400 전역 매핑.
		// developCode 400(공통 BAD_REQUEST) — 신규 에러코드 없음(§D8).
		for (String invalid : List.of("popular", "latest", "VIEWS")) {
			mockMvc.perform(get(GRIDS_URL, REGION_CODE)
					.queryParam("sort", invalid)
					.header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));
		}
	}

	@Test
	@DisplayName("비숫자_limit은_400이다")
	void 비숫자_limit은_400이다() throws Exception {
		mockMvc.perform(get(GRIDS_URL, REGION_CODE)
				.queryParam("limit", "abc")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));
	}

	@Test
	@DisplayName("미존재_regionCode는_regionName_null_카운트_0_빈_배열_200이다")
	void 미존재_regionCode는_regionName_null_카운트_0_빈_배열_200이다() throws Exception {
		// §D2: 미존재 = 404 가 아니라 200 + 0·빈 배열(6404 재사용 안 함). 합성 자체는 서비스 테스트가 검증.
		given(regionExploreService.getRegionGrids(eq("9999999999"), eq(ExploreSort.POPULAR), isNull()))
			.willReturn(new RegionExploreResponseDto("9999999999", null, 0, 0L, List.of()));

		mockMvc.perform(get(GRIDS_URL, "9999999999")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.regionCode").value("9999999999"))
			.andExpect(jsonPath("$.data.regionName").value(nullValue()))
			.andExpect(jsonPath("$.data.gridCount").value(0))
			.andExpect(jsonPath("$.data.videoCount").value(0))
			.andExpect(jsonPath("$.data.grids").isEmpty());
	}

	@Test
	// 검증: FR-SEARCH-15
	@DisplayName("전체_지역_조회는_200과_행정동_리스트를_반환한다")
	void 전체_지역_조회는_200과_행정동_리스트를_반환한다() throws Exception {
		given(regionExploreService.getExploreRegions(USER_ID, "current-cursor"))
			.willReturn(new RegionExplorePage(List.of(
				new RegionGridCountResponseDto(REGION_CODE, "부산광역시 부산진구 부전2동", 5),
				new RegionGridCountResponseDto("1168051500", "서울특별시 강남구 역삼1동", 3)),
				true, "next-cursor"));

		mockMvc.perform(get(EXPLORE_URL).param("cursor", "current-cursor")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.items[0].regionCode").value(REGION_CODE))
			.andExpect(jsonPath("$.data.items[0].regionName").value("부산광역시 부산진구 부전2동"))
			.andExpect(jsonPath("$.data.items[0].gridCount").value(5))
			.andExpect(jsonPath("$.data.items[1].regionCode").value("1168051500"))
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andExpect(jsonPath("$.data.nextCursor").value("next-cursor"));
	}

	@Test
	@DisplayName("전역_콘텐츠가_없으면_200과_빈_페이지다")
	void 전역_콘텐츠가_없으면_200과_빈_페이지다() throws Exception {
		given(regionExploreService.getExploreRegions(USER_ID, null))
			.willReturn(new RegionExplorePage(List.of(), false, null));

		mockMvc.perform(get(EXPLORE_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andExpect(jsonPath("$.data.nextCursor").doesNotExist());
	}

	@Test
	@DisplayName("미인증_요청도_200이고_전체_지역은_개인화_없이_응답한다")
	void 미인증_요청도_200이고_전체_지역은_개인화_없이_응답한다() throws Exception {
		// 2 엔드포인트 전부 비로그인 개방 (MSG-491 — 기존 401 계약 대체). 카드 목록은 principal 을 안 받고,
		// 전체 지역은 userId 자리에 null 이 들어가 개인화 절이 통째로 빠진다.
		given(regionExploreService.getExploreRegions(null, null))
			.willReturn(new RegionExplorePage(List.of(), false, null));

		mockMvc.perform(get(GRIDS_URL, REGION_CODE))
			.andExpect(status().isOk());
		mockMvc.perform(get(EXPLORE_URL))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}
}

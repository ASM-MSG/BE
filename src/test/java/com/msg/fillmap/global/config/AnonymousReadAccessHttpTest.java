package com.msg.fillmap.global.config;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.msg.fillmap.search.dto.TrendingKeywordResponseDto;
import com.msg.fillmap.search.service.PlaceSearchService;
import com.msg.fillmap.search.service.TrendingKeywordQueryService;
import com.msg.fillmap.video.dto.GridHourlyUploadResponseDto;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
import com.msg.fillmap.video.service.RegionExplorePage;
import com.msg.fillmap.video.service.RegionExploreService;
import com.msg.fillmap.video.service.VideoService;
import com.msg.fillmap.zone.dto.ZoneResponseDto;
import com.msg.fillmap.zone.service.ZoneQueryService;

/**
 * 비로그인 지도 조회의 인증 계약 (MSG-469, MSG-491로 3종 추가). "상단 칩은 비로그인, 업로드는 로그인"
 * 원칙(MSG-439·454·467)의 마지막 잔여분이라 검증 대상이 SecurityConfig 한 곳인데, 경로가 zone·search·
 * video 셋에 걸쳐 어느 도메인 패키지에도 온전히 속하지 않아 설정이 있는 global.config 아래에 둔다.
 * 서비스는 전부 목이라 이 테스트의 변수는 매처 등록뿐이다. 열려야 할 여섯이 열렸는지와, 사용자별 값인
 * 격자 조회 4종·AI 경로 추천·비GET·무효 토큰이 함께 풀리지 않았는지를 같이 본다(광역 matcher 회귀 방지).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("비로그인 지도 조회 공개 접근 (SecurityConfig)")
class AnonymousReadAccessHttpTest {

	private static final String GRID_ID = "19422_9582";
	private static final String REGION_CODE = "2644056000";
	private static final long VIDEO_ID = 1042L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ZoneQueryService zoneQueryService;

	@MockitoBean
	private TrendingKeywordQueryService trendingKeywordQueryService;

	@MockitoBean
	private PlaceSearchService placeSearchService;

	@MockitoBean
	private VideoService videoService;

	@MockitoBean
	private RegionExploreService regionExploreService;

	// 검증: FR-ZONE-11, FR-SEARCH-01, FR-SEARCH-07, FR-VIDEO-17, FR-VIDEO-18, FR-MAP-09
	@Test
	@DisplayName("무인증으로 비로그인 개방 6종이 200으로 성공한다")
	void 무인증으로_비로그인_개방_6종이_200으로_성공한다() throws Exception {
		given(zoneQueryService.getZones()).willReturn(List.of(
			new ZoneResponseDto("seomyeon", "서면", "2623051000", 16850, 16866, 11414, 11424, 0)));
		given(trendingKeywordQueryService.findTop10()).willReturn(List.of(
			new TrendingKeywordResponseDto(1, "홍대 카페")));
		given(videoService.getGridGlobalVideos(GRID_ID, null, 20))
			.willReturn(new GridVideoPageResponseDto(List.of(), false, null));
		given(videoService.getGridHourlyUploads(GRID_ID))
			.willReturn(new GridHourlyUploadResponseDto(GRID_ID, List.of()));

		mockMvc.perform(get("/api/zones"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data[0].zoneKey").value("seomyeon"));
		// q 가 빠지면 인가와 무관하게 400 이라 유효한 검색어를 싣는다 (§성공 기준 1)
		mockMvc.perform(get("/api/search/places").param("q", "부산대"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200));
		mockMvc.perform(get("/api/search/trending"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].keyword").value("홍대 카페"));
		// 대표 영상은 후보가 없으면 data null 이 정상 응답이다 — 여기서 보는 것은 인가뿐이다
		mockMvc.perform(get("/api/grids/" + GRID_ID + "/cover"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200));
		mockMvc.perform(get("/api/grids/" + GRID_ID + "/videos"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(false));
		mockMvc.perform(get("/api/grids/" + GRID_ID + "/hourly-uploads"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.gridId").value(GRID_ID));
	}

	// 검증: FR-VIDEO-17, FR-VIDEO-12, FR-VIDEO-16, FR-SEARCH-15
	@Test
	@DisplayName("무인증으로 비로그인 개방 3종이 200으로 성공한다 (MSG-491)")
	void 무인증으로_비로그인_개방_3종이_200으로_성공한다() throws Exception {
		given(regionExploreService.getExploreRegions(isNull(), isNull()))
			.willReturn(new RegionExplorePage(List.of(), false, null));

		// 동 격자 카드 목록은 principal 을 아예 안 받는다 — 여기서 보는 것은 매처 등록뿐이다.
		mockMvc.perform(get("/api/regions/" + REGION_CODE + "/grids"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200));
		mockMvc.perform(get("/api/regions/explore"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(false));
		mockMvc.perform(get("/api/videos/" + VIDEO_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200));
		// 열림만으로는 부족하다. 익명이 userId 자리에 null 로 들어가야 PUBLIC 만 통과하는 판정이 서고,
		// principal.userId() 를 그대로 부르면 401 이 아니라 NPE 500 이 된다.
		then(videoService).should().getVideoPlayback(isNull(), eq(VIDEO_ID));
	}

	// 검증: FR-VIDEO-12
	@Test
	@DisplayName("무인증 재생 경로의 쓰기 메서드는 401로 거절된다 (같은 URL, GET 한정 계약)")
	void 무인증_재생_경로의_쓰기_메서드는_401로_거절된다() throws Exception {
		// /api/videos/* 를 메서드 무제한으로 열면 교체·공개범위 변경·삭제가 함께 풀린다.
		mockMvc.perform(delete("/api/videos/" + VIDEO_ID)).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/videos/" + VIDEO_ID + "/reports")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("무인증 재생 경로의 비숫자 세그먼트는 401로 거절된다 (열거되지 않은 GET 은 닫힌다)")
	void 무인증_재생_경로의_비숫자_세그먼트는_401로_거절된다() throws Exception {
		// videoId 자리를 숫자로 못박지 않으면 나중에 붙는 GET /api/videos/{새경로} 가 열거를 거치지 않고
		// 기본 공개가 된다. 지금은 핸들러가 없어도 매처가 먼저 걸러 401 이어야 한다.
		mockMvc.perform(get("/api/videos/drafts")).andExpect(status().isUnauthorized());
		mockMvc.perform(head("/api/videos/drafts")).andExpect(status().isUnauthorized());
	}

	// 검증: FR-REGION-06, FR-REGION-07
	@Test
	@DisplayName("무인증 내 수집률 조회는 401로 거절된다 (사용자별 값은 열지 않는다)")
	void 무인증_내_수집률_조회는_401로_거절된다() throws Exception {
		// 매처를 /api/regions/** 로 넓히면 여기서 깨진다 — stats 계열은 principal.userId() 를 바로 부른다.
		mockMvc.perform(get("/api/regions/stats")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/regions/stats/by-grid").param("gridId", GRID_ID))
			.andExpect(status().isUnauthorized());
	}

	// 검증: FR-GRID-06
	@Test
	@DisplayName("무인증 개인화 격자 조회 4종은 401로 거절된다 (사용자별 값은 열지 않는다)")
	void 무인증_개인화_격자_조회_4종은_401로_거절된다() throws Exception {
		// 매처를 /api/grids/** 로 넓히면 여기서 깨진다 — 인가 누수이자 principal.userId() NPE 500 가드.
		mockMvc.perform(get("/api/grids/" + GRID_ID)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/grids")
				.param("swLat", "37.50").param("swLng", "127.00")
				.param("neLat", "37.55").param("neLng", "127.05"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/grids/aggregation")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/grids/" + GRID_ID + "/my-videos")).andExpect(status().isUnauthorized());
	}

	// 검증: FR-ROUTE-01
	@Test
	@DisplayName("무인증 AI 경로 추천은 401로 거절된다 (호출마다 유료 AI 호출)")
	void 무인증_AI_경로_추천은_401로_거절된다() throws Exception {
		mockMvc.perform(post("/api/routes/recommend")).andExpect(status().isUnauthorized());
	}

	// 검증: NFR-SEC-10
	@Test
	@DisplayName("무인증 보행 경로 조회는 401로 거절된다 (외부 TMap 한도 소모 경로 — 비로그인 개방 6종에 없음)")
	void 비로그인_보행_경로_조회는_401이다() throws Exception {
		mockMvc.perform(post("/api/routes/walk-paths")).andExpect(status().isUnauthorized());
	}

	// 검증: FR-ZONE-11, FR-SEARCH-01
	@Test
	@DisplayName("무인증 비GET 개방 경로는 401로 거절된다 (GET 한정 계약)")
	void 무인증_비GET_개방_경로는_401로_거절된다() throws Exception {
		mockMvc.perform(post("/api/zones")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/search/places").param("q", "부산대")).andExpect(status().isUnauthorized());
	}

	// 검증: FR-ZONE-11
	@Test
	@DisplayName("공개 GET에서도 무효 토큰은 2401로 거절된다 (토큰 검증 성질 보존)")
	void 공개_GET에서도_무효_토큰은_2401로_거절된다() throws Exception {
		mockMvc.perform(get("/api/zones").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2401));
	}
}

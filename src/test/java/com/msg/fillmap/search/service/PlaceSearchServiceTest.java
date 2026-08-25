package com.msg.fillmap.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.search.dto.PlaceSearchResponseDto;
import com.msg.fillmap.search.exception.SearchErrorCode;
import com.msg.fillmap.search.service.KakaoLocalClient.KakaoPlace;
import com.msg.fillmap.search.service.impl.PlaceSearchServiceImpl;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 장소 검색 서비스 단위 테스트 (MSG-251) — 클라이언트는 mock, DB 무접점. trim 가드(§D3)·gridId 즉석 합성·
 * 주소 표시 규칙(§D2)을 검증한다. 스모크 실측 4곳(2026-07-28, 로컬 PostGIS 실데이터) 격자 기대값을 계약으로
 * 고정해 인코딩 규칙 회귀를 즉시 적발한다(§D7). 검색어 집계 훅(MSG-258 §D1)의 호출 위치도 여기서 검증한다 —
 * 카카오 호출 전 접수라 카카오 실패와 무관하게 집계된다(FR-1).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlaceSearchService — trim 가드·gridId 합성·주소 규칙·집계 훅")
class PlaceSearchServiceTest {

	private static final String SEARCHER_KEY = "42";

	/** 표시명 계산용 합성 구역 (MSG-341) — 서면역 격자(16858_11420) 한 칸만 덮는다. */
	private static final String ZONE_NAME = "m341서면";
	private static final String 서면역_GRID_ID = "16858_11420";

	@Mock
	private KakaoLocalClient kakaoLocalClient;

	@Mock
	private SearchKeywordCommandService searchKeywordCommandService;

	private PlaceSearchServiceImpl placeSearchService;

	@BeforeEach
	void setUp() {
		// zones 는 DB 대신 고정 스냅샷을 주입한다 — 이름 산술은 순수 함수라 카카오 프록시 경로와 독립이다
		GridIndex 서면역 = GridEncoder.decode(서면역_GRID_ID);
		ZoneNameResolver resolver = new ZoneNameResolver(List.of(Zone.builder()
			.zoneKey("m341-search")
			.name(ZONE_NAME)
			.minGridY((int) 서면역.gridY())
			.maxGridY((int) 서면역.gridY())
			.minGridX((int) 서면역.gridX())
			.maxGridX((int) 서면역.gridX())
			.priority(0)
			.build()));
		placeSearchService = new PlaceSearchServiceImpl(kakaoLocalClient, searchKeywordCommandService,
			() -> resolver);
	}

	private KakaoPlace place(String name, double lat, double lng) {
		return new KakaoPlace(name, name + " 지번", name + " 도로명", lat, lng);
	}

	// 검증: FR-SEARCH-02
	@Test
	void 트림_후_빈_검색어는_집계도_카카오_호출도_되지_않는다() {
		// 빈 쿼리를 카카오에 흘리면 카카오가 400 을 내고 쿼터만 소모한다 — 호출 0 이 계약이다(§D3, 234 trim 가드 계승)
		assertThat(placeSearchService.searchPlaces(SEARCHER_KEY, "   ")).isEmpty();

		verifyNoInteractions(kakaoLocalClient);
		verifyNoInteractions(searchKeywordCommandService);   // MSG-258 FR-9
	}

	// 검증: FR-SEARCH-05
	@Test
	void 카카오_호출이_실패해도_검색어는_집계된다() {
		// 집계 훅이 카카오 호출 앞에 있으므로 5502 로 수렴하는 경로에서도 신호가 접수된다(MSG-258 FR-1)
		given(kakaoLocalClient.search("부산대"))
			.willThrow(new ApiException(SearchErrorCode.SEARCH_UPSTREAM_ERROR));

		assertThatThrownBy(() -> placeSearchService.searchPlaces(SEARCHER_KEY, "부산대"))
			.isInstanceOf(ApiException.class);

		verify(searchKeywordCommandService).recordSearch(SEARCHER_KEY, "부산대");
	}

	// 검증: FR-SEARCH-06
	@Test
	void 집계에는_trim된_검색어가_전달된다() {
		given(kakaoLocalClient.search("부산대")).willReturn(List.of(place("부산대학교", 35.23272, 129.08246)));

		placeSearchService.searchPlaces(SEARCHER_KEY, "  부산대  ");

		verify(searchKeywordCommandService).recordSearch(SEARCHER_KEY, "부산대");
	}

	// 검증: FR-SEARCH-01
	@Test
	void 좌표를_격자ID로_즉석_계산해_결과에_싣는다() {
		given(kakaoLocalClient.search("강남역"))
			.willReturn(List.of(place("강남역", 37.4979, 127.0276)));

		List<PlaceSearchResponseDto> results = placeSearchService.searchPlaces(SEARCHER_KEY, "  강남역  ");

		// GridEncoder.encode(37.4979, 127.0276) = 5179 변환 후 floor(y/100)_floor(x/100)
		assertThat(results).hasSize(1);
		assertThat(results.getFirst().gridId()).isEqualTo("19443_9582");
		assertThat(results.getFirst().name()).isEqualTo("강남역");
		assertThat(results.getFirst().lat()).isEqualTo(37.4979);
		assertThat(results.getFirst().lng()).isEqualTo(127.0276);
	}

	// 검증: FR-SEARCH-01
	@Test
	void 부산대_서면역_홍대입구역_광안리_좌표는_스모크_실측_격자ID와_일치한다() {
		// 스모크 실측(스펙 §배경) 격자 셀 내부 좌표 fixture — 이 기대값이 깨지면 격자 인코딩 규칙이 변한 것이다(§D7)
		given(kakaoLocalClient.search("스모크")).willReturn(List.of(
			place("부산대학교", 35.23272, 129.08246),
			place("서면역", 35.15790, 129.05930),
			place("홍대입구역", 37.55650, 126.92390),
			place("광안리해수욕장", 35.15350, 129.11910)));

		List<PlaceSearchResponseDto> results = placeSearchService.searchPlaces(SEARCHER_KEY, "스모크");

		assertThat(results).extracting(PlaceSearchResponseDto::gridId)
			.containsExactly("16941_11439", "16858_11420", "19509_9491", "16854_11474");
	}

	// 검증: FR-ZONE-05
	@Test
	void 장소_검색_결과에_구역_이름이_붙는다() {
		given(kakaoLocalClient.search("서면")).willReturn(List.of(
			place("서면역", 35.15790, 129.05930),
			place("부산대학교", 35.23272, 129.08246)));

		List<PlaceSearchResponseDto> results = placeSearchService.searchPlaces(SEARCHER_KEY, "서면");

		// 합성 구역이 덮는 칸은 서면역 하나뿐 — 구역 밖인 부산대는 두 필드가 null 이고 표시 라벨은 address 가 맡는다
		assertThat(results).extracting(PlaceSearchResponseDto::zoneName).containsExactly(ZONE_NAME, null);
		assertThat(results).extracting(PlaceSearchResponseDto::zoneCell).containsExactly("A-1", null);
	}

	// 검증: FR-ROUTE-03 (계약 변경, Owner A)
	@Test
	void 집계없는_검색도_트림_후_빈_검색어면_카카오_호출이_되지_않는다() {
		// 오버로드가 자체 trim 가드를 갖는다 — 위임하는 2인자 경로 테스트로는 이 가드가 검증되지 않는다
		assertThat(placeSearchService.searchPlaces("   ")).isEmpty();

		verifyNoInteractions(kakaoLocalClient);
	}

	// 검증: FR-ROUTE-03 (계약 변경, Owner A)
	@Test
	void 경로추천의_장소검색은_검색어_집계에_잡히지_않는다() {
		// 경로 추천의 검색어는 기계 조립 문자열 — 집계에 흘리면 인기 검색어가 오염된다 (MSG-457 계약 변경)
		given(kakaoLocalClient.search("서면 축제")).willReturn(List.of(place("서면역", 35.15790, 129.05930)));

		List<PlaceSearchResponseDto> results = placeSearchService.searchPlaces("서면 축제");

		// 결과 규칙(카카오 호출·gridId 합성·표시명)은 기존 경로와 동일하다
		assertThat(results).extracting(PlaceSearchResponseDto::gridId).containsExactly("16858_11420");
		assertThat(results).extracting(PlaceSearchResponseDto::zoneName).containsExactly(ZONE_NAME);
		verifyNoInteractions(searchKeywordCommandService);
	}

	// 검증: FR-SEARCH-01
	@Test
	void 도로명주소가_있으면_도로명을_없으면_지번을_address로_쓴다() {
		// §D2 주소 규칙 — FE 분기 제거용 1필드. 클라이언트가 누락을 "" 로 정규화하므로 isEmpty 분기 하나로 끝난다
		given(kakaoLocalClient.search("주소")).willReturn(List.of(
			new KakaoPlace("도로명있음", "부산 금정구 장전동 40", "부산 금정구 부산대학로 63번길 2", 35.2, 129.0),
			new KakaoPlace("도로명없음", "부산 수영구 광안동 192-20", "", 35.1, 129.1)));

		List<PlaceSearchResponseDto> results = placeSearchService.searchPlaces(SEARCHER_KEY, "주소");

		assertThat(results).extracting(PlaceSearchResponseDto::address)
			.containsExactly("부산 금정구 부산대학로 63번길 2", "부산 수영구 광안동 192-20");
	}
}

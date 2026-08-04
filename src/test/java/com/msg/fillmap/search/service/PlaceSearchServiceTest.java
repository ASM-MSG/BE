package com.msg.fillmap.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.search.dto.PlaceSearchResponseDto;
import com.msg.fillmap.search.exception.SearchErrorCode;
import com.msg.fillmap.search.service.KakaoLocalClient.KakaoPlace;
import com.msg.fillmap.search.service.impl.PlaceSearchServiceImpl;

/**
 * 장소 검색 서비스 단위 테스트 (MSG-251) — 클라이언트는 mock, DB 무접점. trim 가드(§D3)·gridId 즉석 합성·
 * 주소 표시 규칙(§D2)을 검증한다. 스모크 실측 4곳(2026-07-28, 로컬 PostGIS 실데이터) 격자 기대값을 계약으로
 * 고정해 인코딩 규칙 회귀를 즉시 적발한다(§D7). 검색어 집계 훅(MSG-258 §D1)의 호출 위치도 여기서 검증한다 —
 * 카카오 호출 전 접수라 카카오 실패와 무관하게 집계된다(FR-1).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlaceSearchService — trim 가드·gridId 합성·주소 규칙·집계 훅")
class PlaceSearchServiceTest {

	private static final long USER_ID = 42L;

	@Mock
	private KakaoLocalClient kakaoLocalClient;

	@Mock
	private SearchKeywordCommandService searchKeywordCommandService;

	@InjectMocks
	private PlaceSearchServiceImpl placeSearchService;

	private KakaoPlace place(String name, double lat, double lng) {
		return new KakaoPlace(name, name + " 지번", name + " 도로명", lat, lng);
	}

	@Test
	void 트림_후_빈_검색어는_집계도_카카오_호출도_되지_않는다() {
		// 빈 쿼리를 카카오에 흘리면 카카오가 400 을 내고 쿼터만 소모한다 — 호출 0 이 계약이다(§D3, 234 trim 가드 계승)
		assertThat(placeSearchService.searchPlaces(USER_ID, "   ")).isEmpty();

		verifyNoInteractions(kakaoLocalClient);
		verifyNoInteractions(searchKeywordCommandService);   // MSG-258 FR-9
	}

	@Test
	void 카카오_호출이_실패해도_검색어는_집계된다() {
		// 집계 훅이 카카오 호출 앞에 있으므로 5502 로 수렴하는 경로에서도 신호가 접수된다(MSG-258 FR-1)
		given(kakaoLocalClient.search("부산대"))
			.willThrow(new ApiException(SearchErrorCode.SEARCH_UPSTREAM_ERROR));

		assertThatThrownBy(() -> placeSearchService.searchPlaces(USER_ID, "부산대"))
			.isInstanceOf(ApiException.class);

		verify(searchKeywordCommandService).recordSearch(USER_ID, "부산대");
	}

	@Test
	void 집계에는_trim된_검색어가_전달된다() {
		given(kakaoLocalClient.search("부산대")).willReturn(List.of(place("부산대학교", 35.23272, 129.08246)));

		placeSearchService.searchPlaces(USER_ID, "  부산대  ");

		verify(searchKeywordCommandService).recordSearch(USER_ID, "부산대");
	}

	@Test
	void 좌표를_격자ID로_즉석_계산해_결과에_싣는다() {
		given(kakaoLocalClient.search("강남역"))
			.willReturn(List.of(place("강남역", 37.4979, 127.0276)));

		List<PlaceSearchResponseDto> results = placeSearchService.searchPlaces(USER_ID, "  강남역  ");

		// GridEncoder.encode(37.4979, 127.0276) = floor(37.4979/0.0009)_floor(127.0276/0.00115)
		assertThat(results).hasSize(1);
		assertThat(results.getFirst().gridId()).isEqualTo("41664_110458");
		assertThat(results.getFirst().name()).isEqualTo("강남역");
		assertThat(results.getFirst().lat()).isEqualTo(37.4979);
		assertThat(results.getFirst().lng()).isEqualTo(127.0276);
	}

	@Test
	void 부산대_서면역_홍대입구역_광안리_좌표는_스모크_실측_격자ID와_일치한다() {
		// 스모크 실측(스펙 §배경) 격자 셀 내부 좌표 fixture — 이 기대값이 깨지면 격자 인코딩 규칙이 변한 것이다(§D7)
		given(kakaoLocalClient.search("스모크")).willReturn(List.of(
			place("부산대학교", 35.23272, 129.08246),
			place("서면역", 35.15790, 129.05930),
			place("홍대입구역", 37.55650, 126.92390),
			place("광안리해수욕장", 35.15350, 129.11910)));

		List<PlaceSearchResponseDto> results = placeSearchService.searchPlaces(USER_ID, "스모크");

		assertThat(results).extracting(PlaceSearchResponseDto::gridId)
			.containsExactly("39147_112245", "39064_112225", "41729_110368", "39059_112277");
	}

	@Test
	void 도로명주소가_있으면_도로명을_없으면_지번을_address로_쓴다() {
		// §D2 주소 규칙 — FE 분기 제거용 1필드. 클라이언트가 누락을 "" 로 정규화하므로 isEmpty 분기 하나로 끝난다
		given(kakaoLocalClient.search("주소")).willReturn(List.of(
			new KakaoPlace("도로명있음", "부산 금정구 장전동 40", "부산 금정구 부산대학로 63번길 2", 35.2, 129.0),
			new KakaoPlace("도로명없음", "부산 수영구 광안동 192-20", "", 35.1, 129.1)));

		List<PlaceSearchResponseDto> results = placeSearchService.searchPlaces(USER_ID, "주소");

		assertThat(results).extracting(PlaceSearchResponseDto::address)
			.containsExactly("부산 금정구 부산대학로 63번길 2", "부산 수영구 광안동 192-20");
	}
}

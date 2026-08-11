package com.msg.fillmap.search.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.JsonNode;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.search.exception.SearchErrorCode;

/**
 * 카카오 로컬 키워드 검색(keyword.json) 어댑터 (MSG-251 — AiClient 어댑터 선례). webmvc 내장 RestClient 만
 * 쓴다 — 신규 HTTP 의존성 없음. baseUrl·KakaoAK 헤더·타임아웃은 SearchConfig 의 완성 빈 소관이라(§D5)
 * 여기서는 호출·파싱만 한다. 실시간 패스스루 전용 — 결과를 저장·캐시하는 경로 자체가 없다(약관 하드 제약, §D6).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoLocalClient {

	// 카카오 최대 페이지 크기 고정 — size/page 는 API 로 노출하지 않는다(§D4). FE 는 상위 몇 건만 잘라 쓴다.
	private static final int PAGE_SIZE = 15;

	private final RestClient kakaoLocalRestClient;

	/** 키워드 검색 1페이지(카카오 정확도순 ≤15건). 무매치는 빈 리스트 — 예외가 아니다(§D3). */
	public List<KakaoPlace> search(String query) {
		JsonNode response;
		try {
			response = kakaoLocalRestClient.get()
				.uri(uriBuilder -> uriBuilder.path("/v2/local/search/keyword.json")
					.queryParam("query", query)
					.queryParam("size", PAGE_SIZE)
					.build())
				.retrieve()
				.body(JsonNode.class);
		} catch (RestClientException e) {
			// 카카오 5xx·4xx(키/쿼터)·connect/read 타임아웃·비JSON 본문 디코딩 실패 — 전부 여기로 온다(§D3)
			throw upstreamFailure(e);
		}
		try {
			return parse(response);
		} catch (RuntimeException e) {
			// 좌표 문자열 파싱 실패 등 응답 구조 이상 — 부분 성공 없이 전건 실패로 단순화(§D3)
			throw upstreamFailure(e);
		}
	}

	private List<KakaoPlace> parse(JsonNode response) {
		JsonNode documents = response == null ? null : response.get("documents");
		if (documents == null || !documents.isArray()) {
			// 2xx 인데 documents 배열이 없거나 형식이 다르면 스키마 변경/절단 응답 — 빈 결과로 위장시키지 않고
			// 파싱 실패로 취급한다(§D3 "파싱 실패 전부 5502"). path() MissingNode 순회는 조용히 [] 가 되므로 금지.
			throw new IllegalStateException("카카오 응답에 documents 배열이 없거나 형식이 다릅니다");
		}
		List<KakaoPlace> places = new ArrayList<>();
		for (JsonNode document : documents) {
			places.add(new KakaoPlace(
				document.path("place_name").asString(""),
				document.path("address_name").asString(""),
				document.path("road_address_name").asString(""),	// null/누락 → "" 정규화 — §D2 주소 규칙의 입력
				Double.parseDouble(document.path("y").asString("")),	// 카카오는 좌표를 문자열로 준다 — WGS84 직결, 변환 없음
				Double.parseDouble(document.path("x").asString(""))));
		}
		return places;
	}

	/** 업스트림 실패 단일 수렴(5502) — 원인 구분(키/쿼터/타임아웃/파싱)은 운영 진단용 warn 로그로만 남긴다(§D3). */
	private ApiException upstreamFailure(Exception cause) {
		// 예외 메시지에는 요청 URL(?query=)·응답 본문이 실려 검색어가 되돌아올 수 있다 — 클래스명과 상태 코드만
		// 남긴다 (MSG-342 D-2). 원인 구분(키/쿼터=401/403·타임아웃·파싱)은 이 둘로 충분하다.
		String status = cause instanceof RestClientResponseException responseException
			? String.valueOf(responseException.getStatusCode().value())
			: "-";
		log.warn("카카오 로컬 검색 실패 — 5502 로 수렴: cause={}, status={}",
			cause.getClass().getSimpleName(), status);
		return new ApiException(SearchErrorCode.SEARCH_UPSTREAM_ERROR, cause);
	}

	/** 카카오 documents[] 1건의 내부 표현 — lat=카카오 y, lng=카카오 x. */
	public record KakaoPlace(String placeName, String addressName, String roadAddressName, double lat, double lng) {
	}
}

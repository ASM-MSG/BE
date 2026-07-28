package com.msg.fillmap.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 카카오 로컬 키워드 검색 설정 (MSG-251 §D5). AiProperties 관례를 따른 record 바인딩 —
 * @ConfigurationPropertiesScan 이 자동 등록한다.
 *
 * rest-api-key 는 application.yml 에서 ${oauth.kakao.client-id:} 를 참조한다 — 카카오는 OIDC client_id 가
 * 곧 REST API 키(같은 "필맵" 앱)라 신규 시크릿·env 없이 로컬 yml/KAKAO_CLIENT_ID 주입을 자동 승계한다.
 * 앱 분리로 키가 갈라지면 그때 전용 env 로 승격한다(1줄).
 */
@ConfigurationProperties(prefix = "search.kakao")
public record KakaoLocalProperties(
	@DefaultValue("https://dapi.kakao.com") String baseUrl,
	String restApiKey
) {
}

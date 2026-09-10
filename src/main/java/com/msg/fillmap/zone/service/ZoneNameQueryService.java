package com.msg.fillmap.zone.service;

/**
 * 격자 표시명 계산 계약 (MSG-341). Owner A 가 제공하고 Owner B(video·usergrid)가 소비하는 크로스오너 경계면이다.
 * 단건 메서드 {@code name(gridY, gridX)} 를 이 계약 최상위에 두지 않는 이유: 응답 항목마다 zones 조회가 나가는
 * N+1 사용법을 계약 형태로 봉쇄해 "요청당 zones 로드는 상수 회"(FR-8)를 구조로 보장하기 위해서다.
 * 소비처는 매핑 시작 전에 리졸버를 1회 받고, 항목마다 {@link ZoneNameResolver#name(long, long)} 을 부른다.
 * 단건 응답(단일 격자·업로드 확정·재생)도 같은 패턴 한 줄이다.
 */
public interface ZoneNameQueryService {

	/** zones 전체를 1회 로드한 불변 리졸버를 만든다. 요청당 1회만 호출한다. */
	ZoneNameResolver resolver();
}

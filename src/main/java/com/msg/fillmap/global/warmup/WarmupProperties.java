package com.msg.fillmap.global.warmup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 기동 워밍업 설정 (MSG-608). 기본 off — dev·prod 는 env FILLMAP_WARMUP_ENABLED=true 로 켠다
 * (시더 게이트 fillmap.zone.seed.enabled 와 같은 방식). 테스트 컨텍스트마다 localhost 호출 수천 건이
 * 나가지 않게 기본값을 false 로 둔다(D9).
 *
 * @param iterations 대상별 호출 건수(한 바퀴 수가 아니다, D3). 2,000 — 2026-09-27 dev 실측으로 확정(D7)
 * @param gridUserId 격자 뷰포트 조회에 쓸 실존 사용자 id(D4). 없으면 격자 조회만 건너뛴다
 * @param baseUrl    자기 자신 주소. application.yml 이 server.port 로 조립한다
 */
@ConfigurationProperties(prefix = "fillmap.warmup")
public record WarmupProperties(
	@DefaultValue("false") boolean enabled,
	@DefaultValue("2000") int iterations,
	Long gridUserId,
	@DefaultValue("http://localhost:8080") String baseUrl
) {
}

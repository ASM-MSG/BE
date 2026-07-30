package com.msg.fillmap.region.service;

/**
 * 수집률 캐시(region_stats) 갱신 계약 (MSG-155, Owner A 제공 → Owner B 소비). 첫 점령/롤백 시 Owner B 가 호출한다.
 * gridId 의 저장 라벨(grids.region_code — 167 이 쓰기 시점에 중심점 판정으로 저장, 236 equi 치환)로 행정동을
 * 귀속해 그 (user, region) 의 collected_count/progress_rate 를 recompute 한다.
 * recompute 라 방향 무관·멱등 — 점령·롤백 둘 다 같은 메서드. 무라벨(해안·무귀속, region_code IS NULL)이면 no-op(§D2·D3).
 */
public interface RegionStatsCommandService {

	void refresh(long userId, String gridId);
}

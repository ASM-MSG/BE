package com.msg.fillmap.zone.service;

import static com.msg.fillmap.zone.ZoneTestFixtures.KEY_PREFIX;
import static com.msg.fillmap.zone.ZoneTestFixtures.MAX_GRID_Y;
import static com.msg.fillmap.zone.ZoneTestFixtures.MIN_GRID_X;
import static com.msg.fillmap.zone.ZoneTestFixtures.NAME_PREFIX;
import static com.msg.fillmap.zone.ZoneTestFixtures.zone;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.repository.ZoneRepository;

/**
 * 표시명이 요청 시점의 zones 데이터를 반영하는지 통합 검증 (실 PostgreSQL, FR-ZONE-08).
 * {@code ZoneNameQueryServiceImpl.resolver()} 가 요청마다 findAll 을 다시 읽고 프로세스 캐시가 없으므로(MSG-341 D-2),
 * 재시딩이든 수동 UPDATE/DELETE 든 별도 이름 갱신 배치 없이 다음 조회부터 반영돼야 한다.
 * 좌표는 fixture 기본 사각형의 북단 행·14번째 열이라 기대 셀은 "A-14", priority 100 이라 실 시드 구역을 이긴다.
 */
@SpringBootTest
@Transactional
@DisplayName("ZoneNameQueryService 통합 (실 PostgreSQL) — 표시명은 요청 시점 zones 스냅샷")
class ZoneNameQueryServiceIntegrationTest {

	private static final long GRID_Y = MAX_GRID_Y;
	private static final long GRID_X = MIN_GRID_X + 13;

	@Autowired
	private ZoneNameQueryService zoneNameQueryService;

	@Autowired
	private ZoneRepository zoneRepository;

	@Autowired
	private EntityManager em;

	// 검증: FR-ZONE-08
	@Test
	@DisplayName("구역 이름을 바꾸면 배치 없이 다음 조회부터 바뀐 이름이 반환된다")
	void 구역_이름을_바꾸면_다음_조회부터_바뀐_이름이_반환된다() {
		zoneRepository.saveAndFlush(zone("rename", "개명전", 100));

		assertThat(zoneNameQueryService.resolver().name(GRID_Y, GRID_X))
			.isEqualTo(new ZoneCellName(NAME_PREFIX + "개명전", "A-14"));

		// 수동 UPDATE 를 흉내낸 벌크 갱신 — 영속성 컨텍스트를 비워 다음 조회가 순수 DB 재조회만으로 반영되는지 본다
		em.createQuery("UPDATE Zone z SET z.name = :name WHERE z.zoneKey = :key")
			.setParameter("name", NAME_PREFIX + "개명후")
			.setParameter("key", KEY_PREFIX + "rename")
			.executeUpdate();
		em.clear();

		assertThat(zoneNameQueryService.resolver().name(GRID_Y, GRID_X))
			.isEqualTo(new ZoneCellName(NAME_PREFIX + "개명후", "A-14"));
	}

	// 검증: FR-ZONE-08
	@Test
	@DisplayName("구역을 삭제하면 배치 없이 다음 조회부터 구역 밖(NONE — 행정동 폴백 전제)으로 반환된다")
	void 구역을_삭제하면_다음_조회부터_구역_밖_폴백으로_반환된다() {
		Zone seeded = zoneRepository.saveAndFlush(zone("remove", "삭제될구역", 100));

		assertThat(zoneNameQueryService.resolver().name(GRID_Y, GRID_X))
			.isEqualTo(new ZoneCellName(NAME_PREFIX + "삭제될구역", "A-14"));

		zoneRepository.delete(seeded);
		em.flush();

		assertThat(zoneNameQueryService.resolver().name(GRID_Y, GRID_X)).isEqualTo(ZoneCellName.NONE);
	}
}

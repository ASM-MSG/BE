package com.msg.fillmap.zone.service;

import static org.assertj.core.api.Assertions.assertThat;

import static com.msg.fillmap.zone.ZoneTestFixtures.KEY_PREFIX;
import static com.msg.fillmap.zone.ZoneTestFixtures.zone;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.zone.dto.ZoneResponseDto;
import com.msg.fillmap.zone.repository.ZoneRepository;

/**
 * ZoneQueryService 목록 조회 (실 PostgreSQL) — 합성 zone_key·롤백 격리 (MSG-234 §D3).
 * 컨트롤러 테스트는 서비스를 mock 하므로 impl·DTO 매핑은 여기가 실행형 검증이다
 * (검색 제거로 SearchServiceTest 가 지워지며 생긴 커버리지 공백 복원 — 2026-07-28).
 */
@SpringBootTest
@Transactional
@DisplayName("ZoneQueryService 목록 조회 (실 PostgreSQL) — 합성 zone_key·롤백 격리")
class ZoneQueryServiceTest {

	@Autowired
	private ZoneQueryService zoneQueryService;

	@Autowired
	private ZoneRepository zoneRepository;

	@Test
	@DisplayName("저장된 zone 의 전 필드가 DTO 로 매핑된다 (zoneKey 노출·id 비노출 §D5)")
	void 저장된_zone의_전_필드가_DTO로_매핑된다() {
		zoneRepository.saveAndFlush(zone("svc-map", "서비스매핑", 45120, 45125, 112230, 112240, 7));

		ZoneResponseDto dto = zoneQueryService.getZones().stream()
			.filter(z -> z.zoneKey().equals(KEY_PREFIX + "svc-map"))
			.findFirst().orElseThrow();

		assertThat(dto.name()).isEqualTo("m234서비스매핑");
		assertThat(dto.regionCode()).isNull(); // 합성 zone 은 FK 충돌 회피로 무귀속
		assertThat(dto.minGridY()).isEqualTo(45120);
		assertThat(dto.maxGridY()).isEqualTo(45125);
		assertThat(dto.minGridX()).isEqualTo(112230);
		assertThat(dto.maxGridX()).isEqualTo(112240);
		assertThat(dto.priority()).isEqualTo(7);
	}

	@Test
	@DisplayName("저장한 만큼 목록에 나타나고, 저장 전에는 없다 (시딩 전 빈 상태 §D7)")
	void 저장한_만큼_목록에_나타나고_저장_전에는_없다() {
		List<ZoneResponseDto> before = zoneQueryService.getZones();
		assertThat(before).noneMatch(z -> z.zoneKey().startsWith(KEY_PREFIX + "svc-list"));

		zoneRepository.saveAndFlush(zone("svc-list-1", "리스트일", 1));
		zoneRepository.saveAndFlush(zone("svc-list-2", "리스트이", 45130, 45135, 112250, 112260, 2));

		List<ZoneResponseDto> after = zoneQueryService.getZones();
		assertThat(after.stream().filter(z -> z.zoneKey().startsWith(KEY_PREFIX + "svc-list"))).hasSize(2);
	}
}

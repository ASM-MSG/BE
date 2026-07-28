package com.msg.fillmap.zone.seed;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.zone.repository.ZoneRepository;

/**
 * 구역(zone) 시더 (MSG-234 §D7 — MSG-154 RegionSeeder 모방). 기본 off —
 * {@code fillmap.zone.seed.enabled=true} 일 때만 앱 기동 시 1회 zones.json 을 멱등 UPSERT(zone_key 자연키) 한다.
 * 빈 배열은 정상(성공 기준 8 — zones 0행이어도 전 시스템이 행정동 폴백으로 동작)이라 fail-fast 하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZoneSeeder implements ApplicationRunner {

	private final ZoneRepository zoneRepository;
	private final ObjectMapper objectMapper;

	@Value("${fillmap.zone.seed.enabled:false}")
	private boolean enabled;

	@Value("${fillmap.zone.seed.path:seed/zones.json}")
	private String path;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!enabled) {
			return;
		}
		int loaded = seed(new ClassPathResource(path));
		log.info("zone 시딩 완료 — {} 건 UPSERT", loaded);
	}

	/**
	 * JSON 배열을 읽어 항목마다 zone_key 자연키로 멱등 UPSERT 한다. 트랜잭션은 호출자(run 또는 테스트)가 연다.
	 * 적재 건수를 반환한다.
	 */
	public int seed(Resource resource) {
		List<ZoneSeed> seeds = read(resource);
		for (ZoneSeed s : seeds) {
			zoneRepository.upsert(
				s.zoneKey(), s.name(), s.regionCode(),
				s.minGridY(), s.maxGridY(), s.minGridX(), s.maxGridX(),
				s.priority() == null ? 0 : s.priority());
		}
		return seeds.size();
	}

	private List<ZoneSeed> read(Resource resource) {
		try (InputStream in = resource.getInputStream()) {
			return List.of(objectMapper.readValue(in, ZoneSeed[].class));
		} catch (IOException e) {
			throw new IllegalStateException("zone 시드 파일을 읽을 수 없습니다: " + resource.getDescription(), e);
		}
	}
}

package com.msg.fillmap.region.seed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.region.repository.RegionRepository;

/**
 * 전국 행정동 GeoJSON 을 regions 에 멱등 적재하는 게이트 시더 (MSG-154, D2).
 * 기본 off — {@code fillmap.region.seed.enabled=true} 일 때만 앱 기동 시 1회 실행한다.
 * 일반 부팅·통합 테스트에서는 early return 으로 아무 것도 하지 않는다(성공 기준 5).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegionSeeder implements ApplicationRunner {

	// ponytail: 명목 100×100m 근사(서울 +1.5%~부산 +4.7% 과대). 보정 필요 시 위도별 실측 면적으로 교체
	private static final long CELL_AREA_M2 = 10_000L;

	private final RegionRepository regionRepository;
	private final RegionGeoJsonReader reader;

	@Value("${fillmap.region.seed.enabled:false}")
	private boolean enabled;

	@Value("${fillmap.region.seed.geojson-path:data/region/HangJeongDong_ver20260701.geojson}")
	private String geojsonPath;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!enabled) {
			return;
		}
		SeedResult result = seed(Path.of(geojsonPath));
		log.info("region 시딩 완료 — 적재 {} 건, 건너뜀 {} 건", result.loaded(), result.skipped());
	}

	/**
	 * 지정 GeoJSON 을 파싱해 row 별 native UPSERT 로 적재한다.
	 * 파일이 없거나 유효 feature 가 0건이면 명확한 예외로 조기 실패 — 잘못된 파일이 빈 regions 로 조용히 넘어가지 않게.
	 * 트랜잭션은 호출자(run 또는 테스트)가 연다.
	 */
	public SeedResult seed(Path path) {
		if (!Files.isReadable(path)) {
			throw new IllegalStateException(
				"행정동 GeoJSON 파일을 읽을 수 없습니다: " + path.toAbsolutePath()
					+ " (scripts/download-region-geojson.sh 로 먼저 내려받으세요)");
		}

		RegionGeoJsonReader.Result parsed;
		try (InputStream in = Files.newInputStream(path)) {
			parsed = reader.read(in);
		} catch (IOException e) {
			throw new IllegalStateException("행정동 GeoJSON 읽기에 실패했습니다: " + path.toAbsolutePath(), e);
		}

		if (parsed.features().isEmpty()) {
			throw new IllegalStateException(
				"행정동 GeoJSON 에서 유효한 feature 를 0건 파싱했습니다 (건너뜀 " + parsed.skipped() + " 건): "
					+ path.toAbsolutePath() + " — 파일 내용과 속성 스키마(adm_cd2·adm_nm·geometry)를 확인하세요");
		}

		for (RegionFeature feature : parsed.features()) {
			regionRepository.upsert(
				feature.regionCode(), feature.regionName(), feature.parentCode(),
				feature.geometryJson(), CELL_AREA_M2);
		}
		return new SeedResult(parsed.features().size(), parsed.skipped());
	}

	public record SeedResult(int loaded, int skipped) {
	}
}

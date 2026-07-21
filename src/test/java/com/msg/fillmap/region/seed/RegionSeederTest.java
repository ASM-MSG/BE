package com.msg.fillmap.region.seed;

import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.region.repository.RegionRepository;

@SpringBootTest
@Transactional
@DisplayName("RegionSeeder 멱등성·게이트 (실 PostGIS) — 합성 코드·롤백 격리")
class RegionSeederTest {

	private static final String POLYGON = rectanglePolygonJson(127.0, 37.5, 127.001, 37.501);

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private RegionGeoJsonReader reader;

	@Autowired
	private EntityManager em;

	@TempDir
	private Path tempDir;

	private RegionSeeder newSeeder(boolean enabled, String geojsonPath) {
		RegionSeeder seeder = new RegionSeeder(regionRepository, reader);
		ReflectionTestUtils.setField(seeder, "enabled", enabled);
		ReflectionTestUtils.setField(seeder, "geojsonPath", geojsonPath);
		return seeder;
	}

	/** code→name 각 feature 한 건씩 담은 FeatureCollection 파일을 tempDir 에 쓴다. */
	private Path writeGeoJson(String filename, String... codeNamePairs) throws IOException {
		StringBuilder features = new StringBuilder();
		for (int i = 0; i < codeNamePairs.length; i += 2) {
			if (i > 0) {
				features.append(",");
			}
			features.append("""
				{"type":"Feature","properties":{"adm_cd2":"%s","adm_nm":"%s"},"geometry":%s}"""
				.formatted(codeNamePairs[i], codeNamePairs[i + 1], POLYGON));
		}
		Path file = tempDir.resolve(filename);
		Files.writeString(file, "{\"type\":\"FeatureCollection\",\"features\":[" + features + "]}");
		return file;
	}

	private long syntheticCount() {
		em.flush();
		Number count = (Number) em.createNativeQuery(
				"SELECT COUNT(*) FROM regions WHERE region_code LIKE '999%'")
			.getSingleResult();
		return count.longValue();
	}

	private String nameOf(String code) {
		em.flush();
		return (String) em.createNativeQuery("SELECT region_name FROM regions WHERE region_code = :code")
			.setParameter("code", code)
			.getSingleResult();
	}

	@Test
	@DisplayName("시더를 두 번 실행해도 regions COUNT 가 동일하다 (멱등)")
	void 시더를_두번_실행해도_regions_COUNT가_동일하다() throws IOException {
		Path file = writeGeoJson("idem.geojson",
			syntheticCode("100"), "합성일동", syntheticCode("101"), "합성이동");
		RegionSeeder seeder = newSeeder(true, file.toString());

		seeder.seed(file);
		long afterFirst = syntheticCount();
		seeder.seed(file);
		long afterSecond = syntheticCount();

		assertThat(afterFirst).isEqualTo(2L);
		assertThat(afterSecond).isEqualTo(2L);
	}

	@Test
	@DisplayName("시더 재실행 시 변경된 속성이 UPSERT 로 반영된다")
	void 시더_재실행시_변경된_속성이_UPSERT로_반영된다() throws IOException {
		String code = syntheticCode("102");
		RegionSeeder seeder = newSeeder(true, "unused");

		seeder.seed(writeGeoJson("v1.geojson", code, "옛이름동"));
		assertThat(nameOf(code)).isEqualTo("옛이름동");

		seeder.seed(writeGeoJson("v2.geojson", code, "새이름동"));
		assertThat(nameOf(code)).isEqualTo("새이름동");
	}

	@Test
	@DisplayName("seed 플래그가 꺼져 있으면 시더는 실행되지 않는다 (게이트)")
	void seed_프로파일이_아니면_시더는_실행되지_않는다() throws IOException {
		Path file = writeGeoJson("gate.geojson", syntheticCode("103"), "합성동");
		long before = syntheticCount();

		newSeeder(false, file.toString()).run(emptyArgs());

		assertThat(syntheticCount()).isEqualTo(before);
	}

	@Test
	@DisplayName("플래그가 켜져 있으면 run 이 실제로 적재한다 (게이트 반대 방향)")
	void 플래그가_켜져있으면_run이_실제로_적재한다() throws IOException {
		String code = syntheticCode("104");
		Path file = writeGeoJson("on.geojson", code, "합성동");

		newSeeder(true, file.toString()).run(emptyArgs());

		assertThat(nameOf(code)).isEqualTo("합성동");
	}

	@Test
	@DisplayName("유효한 feature 가 0건이면 조용히 성공하지 않고 예외로 실패한다 (fail-fast)")
	void 유효한_feature가_0건이면_예외로_실패한다() throws IOException {
		Path file = tempDir.resolve("wrong-schema.geojson");
		Files.writeString(file, """
			{"type":"FeatureCollection","features":[
				{"type":"Feature","properties":{"wrong_key":"9990000000"},"geometry":%s}]}""".formatted(POLYGON));

		assertThatThrownBy(() -> newSeeder(true, file.toString()).seed(file))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("0건");
	}

	@Test
	@DisplayName("GeoJSON 파일이 없으면 명확한 예외로 실패한다")
	void GeoJSON파일이_없으면_명확한_예외로_실패한다() {
		Path missing = tempDir.resolve("does-not-exist.geojson");

		assertThatThrownBy(() -> newSeeder(true, missing.toString()).seed(missing))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("GeoJSON");
	}

	private static ApplicationArguments emptyArgs() {
		return new org.springframework.boot.DefaultApplicationArguments();
	}
}

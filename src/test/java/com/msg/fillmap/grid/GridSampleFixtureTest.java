package com.msg.fillmap.grid;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msg.fillmap.grid.GridEncoder.GridPoint;

/**
 * FE 정합성 픽스처 계약 테스트 (MSG-347 FR-9).
 * {@code src/test/resources/fixtures/grid-epsg5179-samples.json} 은 프론트(proj4js) 구현 검증에 쓰라고
 * 내주는 자료다. 값은 PROJ(pyproj)로 생성했으므로 여기서 서버 구현(Proj4J)으로 전수 재계산해 일치를 확인하면
 * 두 PROJ 구현이 같은 격자를 낸다는 근거가 된다 — FE 는 이 파일과 계약 문자열만 가지고 자기 구현을 검증한다.
 */
// 검증: FR-GRID-05
@DisplayName("FE 정합성 샘플 픽스처 (grid-epsg5179-samples.json)")
class GridSampleFixtureTest {

	// PROJ 구현 간 수치 오차 허용치(도). 1e-7° ≈ 1cm — 셀 꼭짓점 비교용이고 격자 ID 는 정확 일치를 본다.
	private static final Offset<Double> CORNER_TOLERANCE_DEG = Offset.offset(1.0e-7);

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Sample(double lat, double lon, long gridY, long gridX, String gridId,
		List<GridPoint> cellCorners) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Fixture(String crsDefinition, int cellSizeMeters, List<Sample> samples) {
	}

	private static Fixture loadFixture() {
		try (InputStream in = GridSampleFixtureTest.class.getResourceAsStream("/fixtures/grid-epsg5179-samples.json")) {
			assertThat(in).as("픽스처 파일(/fixtures/grid-epsg5179-samples.json) 존재").isNotNull();
			return new ObjectMapper().readValue(in, Fixture.class);
		} catch (IOException e) {
			throw new IllegalStateException("픽스처 로드 실패", e);
		}
	}

	@Test
	void 픽스처의_좌표계_정의는_서버_계약_상수와_같다() {
		// FE 가 읽는 파일과 서버 상수가 글자 단위로 같아야 경계 근처 셀이 어긋나지 않는다.
		Fixture fixture = loadFixture();

		assertThat(fixture.crsDefinition()).isEqualTo(GridConstants.CRS_DEF_EPSG5179);
		assertThat(fixture.cellSizeMeters()).isEqualTo(GridConstants.CELL_SIZE_METERS);
	}

	@Test
	void 정합성_샘플_200건은_GridEncoder_재계산과_전수_일치한다() {
		Fixture fixture = loadFixture();
		assertThat(fixture.samples()).hasSize(200);

		for (Sample sample : fixture.samples()) {
			String gridId = GridEncoder.encode(sample.lat(), sample.lon());
			assertThat(gridId).as("(%s, %s) 의 gridId", sample.lat(), sample.lon()).isEqualTo(sample.gridId());
			assertThat(gridId).isEqualTo(sample.gridY() + "_" + sample.gridX());

			List<GridPoint> ring = GridEncoder.bbox(gridId);
			assertThat(sample.cellCorners()).as("%s 의 꼭짓점 수", gridId).hasSize(4);
			for (int i = 0; i < 4; i++) {
				assertThat(ring.get(i).lat()).as("%s 의 %d번째 꼭짓점 위도", gridId, i)
					.isCloseTo(sample.cellCorners().get(i).lat(), CORNER_TOLERANCE_DEG);
				assertThat(ring.get(i).lon()).as("%s 의 %d번째 꼭짓점 경도", gridId, i)
					.isCloseTo(sample.cellCorners().get(i).lon(), CORNER_TOLERANCE_DEG);
			}
		}
	}
}

package com.msg.fillmap.region.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

// 검증: FR-REGION-01
class RegionGeoJsonReaderTest {

	private final RegionGeoJsonReader reader = new RegionGeoJsonReader(new ObjectMapper());

	private static final String ONE_FEATURE = """
		{
		  "type": "FeatureCollection",
		  "features": [
		    {
		      "type": "Feature",
		      "properties": {"adm_nm": "서울특별시 종로구 사직동", "adm_cd2": "1111053000", "sgg": "11110"},
		      "geometry": {"type": "MultiPolygon", "coordinates":
		        [[[[127.0,37.5],[127.001,37.5],[127.001,37.501],[127.0,37.501],[127.0,37.5]]]]}
		    }
		  ]
		}
		""";

	private RegionGeoJsonReader.Result read(String json) {
		InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
		return reader.read(in);
	}

	@Test
	void FeatureCollection에서_adm_cd2를_region_code로_추출한다() {
		RegionFeature feature = read(ONE_FEATURE).features().get(0);

		assertThat(feature.regionCode()).isEqualTo("1111053000");
	}

	@Test
	void adm_nm을_region_name으로_추출한다() {
		RegionFeature feature = read(ONE_FEATURE).features().get(0);

		assertThat(feature.regionName()).isEqualTo("서울특별시 종로구 사직동");
	}

	@Test
	void adm_cd2_앞_5자리를_parent_code로_파생한다() {
		RegionFeature feature = read(ONE_FEATURE).features().get(0);

		assertThat(feature.parentCode()).isEqualTo("11110");
	}

	@Test
	void geometry_노드를_JSON_문자열로_그대로_추출한다() {
		RegionFeature feature = read(ONE_FEATURE).features().get(0);

		// ST_GeomFromGeoJSON 입력용 — geometry 노드만, properties 는 섞이지 않는다.
		assertThat(feature.geometryJson())
			.contains("MultiPolygon")
			.contains("coordinates")
			.doesNotContain("adm_cd2");
	}

	@Test
	void 필수속성_누락_feature는_건너뛴다() {
		String withMissing = """
			{
			  "type": "FeatureCollection",
			  "features": [
			    {"type": "Feature",
			     "properties": {"adm_nm": "정상동", "adm_cd2": "1111053000"},
			     "geometry": {"type": "MultiPolygon", "coordinates": [[[[127.0,37.5],[127.001,37.5],
			       [127.001,37.501],[127.0,37.501],[127.0,37.5]]]]}},
			    {"type": "Feature",
			     "properties": {"adm_nm": "코드없음동"},
			     "geometry": {"type": "MultiPolygon", "coordinates": [[[[127.0,37.5],[127.001,37.5],
			       [127.001,37.501],[127.0,37.501],[127.0,37.5]]]]}},
			    {"type": "Feature",
			     "properties": {"adm_nm": "지오메트리없음동", "adm_cd2": "1111054000"}}
			  ]
			}
			""";

		RegionGeoJsonReader.Result result = read(withMissing);

		assertThat(result.features()).hasSize(1);
		assertThat(result.features().get(0).regionName()).isEqualTo("정상동");
		assertThat(result.skipped()).isEqualTo(2);
	}
}

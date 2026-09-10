package com.msg.fillmap.region.seed;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 전국 행정동 GeoJSON FeatureCollection 파서 (MSG-154, 순수 로직 · DB 무관).
 * 각 feature 에서 시딩 입력(region_code·region_name·parent_code·geometryJson)을 추출하고,
 * 필수 속성(adm_cd2·adm_nm·geometry)이 누락된 feature 는 건너뛰며 개수를 집계한다(전량 실패 방지).
 */
@Component
public class RegionGeoJsonReader {

	private static final String PROP_CODE = "adm_cd2";
	private static final String PROP_NAME = "adm_nm";
	// 시군구 코드 = adm_cd2 앞 5자리. HangJeongDong_ver20260701 전수(3,558건)에서 sgg 속성과 일치 확인(Q1).
	private static final int PARENT_CODE_LENGTH = 5;

	private final ObjectMapper objectMapper;

	public RegionGeoJsonReader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public Result read(InputStream in) {
		JsonNode root = objectMapper.readTree(in);
		List<RegionFeature> features = new ArrayList<>();
		int skipped = 0;

		for (JsonNode feature : root.path("features")) {
			JsonNode properties = feature.path("properties");
			JsonNode geometry = feature.path("geometry");
			String regionCode = text(properties, PROP_CODE);
			String regionName = text(properties, PROP_NAME);

			if (regionCode == null || regionName == null || geometry.isMissingNode() || geometry.isNull()) {
				skipped++;
				continue;
			}

			features.add(new RegionFeature(regionCode, regionName, parentCode(regionCode), geometry.toString()));
		}

		return new Result(features, skipped);
	}

	private static String parentCode(String regionCode) {
		return regionCode.length() >= PARENT_CODE_LENGTH ? regionCode.substring(0, PARENT_CODE_LENGTH) : null;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asString();
		return text.isBlank() ? null : text;
	}

	public record Result(List<RegionFeature> features, int skipped) {
	}
}

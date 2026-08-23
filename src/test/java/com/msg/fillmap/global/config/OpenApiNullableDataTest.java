package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * data 가 null 일 수 있는 응답의 OpenAPI nullable 표기 가드 (MSG-346).
 *
 * <p>{@link OpenApiConfig} 의 커스터마이저가 경로 목록 기반이라, 경로가 개명되면 표기가 조용히
 * 빠질 수 있다. 이 테스트가 실제 /v3/api-docs 산출물에서 전수 확인해 그 낡음을 잡는다.
 * 대상 목록은 커스터마이저와 의도적으로 중복 선언한다 — 한쪽만 고치면 여기서 깨진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("OpenAPI data nullable 표기 (MSG-346)")
class OpenApiNullableDataTest {

	private static final List<String> NULLABLE_DATA_GET_PATHS = List.of(
		"/api/grids/{gridId}/cover",
		"/api/regions/reverse-geocode",
		"/api/regions/stats/by-point",
		"/api/regions/stats/by-grid");

	@Autowired
	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void data가_null일_수_있는_응답_전수는_스키마에_null_허용이_실린다() throws Exception {
		JsonNode docs = fetchApiDocs();
		for (String path : NULLABLE_DATA_GET_PATHS) {
			JsonNode data = dataProperty(docs, path);
			JsonNode anyOf = data.path("anyOf");
			assertThat(anyOf.isArray())
				.as("%s 의 200 래퍼 스키마 data 가 anyOf 패턴이 아니다 (OpenAPI 3.1 은 nullable 대신 type null)", path)
				.isTrue();
			assertThat(hasNullType(anyOf))
				.as("%s 의 data anyOf 에 {type: \"null\"} 분기가 없다", path)
				.isTrue();
		}
	}

	@Test
	void data가_항상_있는_응답은_null_허용이_붙지_않는다() throws Exception {
		JsonNode docs = fetchApiDocs();
		JsonNode data = dataProperty(docs, "/api/collections/summary");
		assertThat(data.path("anyOf").isArray())
			.as("data 가 항상 있는 응답까지 null 허용으로 오염되면 안 된다")
			.isFalse();
	}

	// 검증: FR-NOTI-15
	@Test
	@DisplayName("알림 설정 category 스키마 허용값은 8종이고 MODERATION 이 없다 — 수신 거부 불가 비노출 (MSG-417·442)")
	void 알림_설정_category_스키마_허용값에_MODERATION이_없다() throws Exception {
		JsonNode category = fetchApiDocs().path("components").path("schemas")
			.path("CategoryPreferenceDto").path("properties").path("category");
		JsonNode allowed = category.path("enum");
		assertThat(allowed.isArray())
			.as("category 가 인라인 enum 스키마가 아니다 — allowableValues 가 공유 $ref 로 빠지면 제약이 사라진다")
			.isTrue();
		List<String> values = new ArrayList<>();
		allowed.forEach(value -> values.add(value.asString("")));
		assertThat(values).containsExactly("BADGE", "HOTZONE", "REMIND", "VIDEO", "WEEKLY", "FRIEND",
			"MISSION_NEARBY", "EVENT");
	}

	// 검증: FR-EVENT-06
	@Test
	@DisplayName("알림함 목록 category 스키마 허용값에 EVENT 가 실린다 — 서버 발송형이라 목록에 나타난다 (MSG-442)")
	void 알림함_목록_category_스키마_허용값에_EVENT가_실린다() throws Exception {
		JsonNode allowed = fetchApiDocs().path("components").path("schemas")
			.path("NotificationItemResponseDto").path("properties").path("category").path("enum");
		assertThat(allowed.isArray())
			.as("category 가 인라인 enum 스키마가 아니다 — allowableValues 가 공유 $ref 로 빠지면 제약이 사라진다")
			.isTrue();
		List<String> values = new ArrayList<>();
		allowed.forEach(value -> values.add(value.asString("")));
		// MISSION_NEARBY 는 서버 발송 경로가 없어 여전히 빠진다 (MSG-418 의도 제외)
		assertThat(values).containsExactly("BADGE", "HOTZONE", "REMIND", "VIDEO", "WEEKLY", "FRIEND",
			"MODERATION", "EVENT");
	}

	@Test
	@DisplayName("nullable $ref 속성은 형제 type 없이 anyOf 로 나간다 — FE 생성 타입이 null 로만 좁혀지던 문제 (MSG-460)")
	void nullable_ref_속성은_anyOf로_나간다() throws Exception {
		JsonNode schemas = fetchApiDocs().path("components").path("schemas");
		List<String[]> targets = List.of(
			new String[] {"MissionDetailResponseDto", "progress"},
			new String[] {"GridAggregationResponseDto", "currentRegion"});
		for (String[] target : targets) {
			JsonNode property = schemas.path(target[0]).path("properties").path(target[1]);
			assertThat(property.isObject()).as("%s.%s 속성이 없다", target[0], target[1]).isTrue();
			assertThat(property.has("$ref"))
				.as("%s.%s 에 $ref 형제 키가 남아 있다 — anyOf 안으로 들어가야 한다", target[0], target[1])
				.isFalse();
			assertThat(property.has("type"))
				.as("%s.%s 에 형제 type 이 남아 있다", target[0], target[1])
				.isFalse();
			assertThat(hasNullType(property.path("anyOf")))
				.as("%s.%s 의 anyOf 에 {type: \"null\"} 분기가 없다", target[0], target[1])
				.isTrue();
		}
	}

	private boolean hasNullType(JsonNode anyOf) {
		for (JsonNode branch : anyOf) {
			JsonNode type = branch.path("type");
			if ("null".equals(type.asString("")) || (type.isArray() && type.toString().contains("\"null\""))) {
				return true;
			}
		}
		return false;
	}

	private JsonNode fetchApiDocs() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body);
	}

	/** 경로의 GET 200 응답 $ref 를 components 로 따라가 래퍼 스키마의 data 속성을 꺼낸다. */
	private JsonNode dataProperty(JsonNode docs, String path) {
		JsonNode content = docs.path("paths").path(path).path("get").path("responses").path("200").path("content");
		assertThat(content.isObject()).as("%s 의 GET 200 content 가 없다", path).isTrue();
		JsonNode schema = content.iterator().next().path("schema");
		String ref = schema.path("$ref").asString("");
		assertThat(ref).as("%s 의 200 응답이 $ref 스키마가 아니다", path).isNotEmpty();
		String name = ref.substring(ref.lastIndexOf('/') + 1);
		return docs.path("components").path("schemas").path(name).path("properties").path("data");
	}
}

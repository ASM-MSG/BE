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
 * 원문 passthrough 필드가 <b>발행된 명세</b>에서도 객체로 선언되는지 본다.
 *
 * <p><b>왜 발행값을 보는가</b> — `MissionShape.PathShape.line` 은 `@JsonRawValue` 라 자바 타입은
 * `String` 이지만 와이어에는 JSON <b>객체</b>가 나간다. 여기에 `@Schema(type = "object",
 * nullable = true)` 를 붙여 뒀는데도 springdoc 이 `nullable` 을 처리하면서 자바 타입으로 `type` 을
 * 다시 만들어 `["string","null"]` 로 발행했다. 소스만 읽으면 맞아 보이고 발행값만 틀린 사고라,
 * 애노테이션을 검사하는 리플렉션 가드(`ResponseSchemaNullabilityTest`)로는 잡히지 않는다.
 *
 * <p>실제 피해는 프런트엔드에서 났다. 생성 타입이 `line: string | null` 로 떨어져 FE 가
 * `JSON.parse(line)` 을 썼고, 객체를 넘겨받은 파싱이 매번 실패해 코스 경로가 통째로 빈 배열이 됐다.
 * 지도에는 실제 산책로 대신 포토스팟을 직선으로 이은 선이 그려졌다(바다를 가로지르는 선).
 *
 * <p>그래서 이 테스트는 컨텍스트를 띄워 `/v3/api-docs` 를 실제로 받아 확인한다 — springdoc 버전을
 * 올리다 같은 회귀가 나면 여기서 걸린다. 데이터에 의존하지 않아 공유 로컬 DB 의 시드와 무관하게
 * 결정적이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("원문 passthrough 필드의 명세 발행 계약")
class RawJsonSchemaPublicationTest {

	/** `@JsonRawValue` 로 원문을 재발행하는 필드 — 스키마가 자바 타입(String)을 주장하면 안 된다. */
	private record RawField(String schemaName, String fieldName) {
	}

	private static final List<RawField> RAW_JSON_FIELDS = List.of(
		new RawField("PathShape", "line")
	);

	@Autowired
	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("원문 passthrough 필드는 object 로 발행되고 string 으로 발행되지 않는다")
	void 원문_passthrough_필드는_object_로_발행된다() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		JsonNode schemas = objectMapper.readTree(body).path("components").path("schemas");

		List<String> violations = new ArrayList<>();
		for (RawField target : RAW_JSON_FIELDS) {
			String schemaName = target.schemaName();
			String fieldName = target.fieldName();
			JsonNode field = findProperty(schemas.path(schemaName), fieldName);
			if (field.isMissingNode()) {
				violations.add(schemaName + "." + fieldName + " — 명세에서 찾지 못했다");
				continue;
			}
			List<String> types = declaredTypes(field);
			if (!types.contains("object") || types.contains("string")) {
				violations.add(schemaName + "." + fieldName + " — type=" + types + " (object 여야 하고 string 이면 안 된다)");
			}
		}
		assertThat(violations).isEmpty();
	}

	/** allOf 로 쪼개져 발행되므로(상속 표현) 가지를 훑어 프로퍼티를 찾는다. */
	private JsonNode findProperty(JsonNode schema, String fieldName) {
		JsonNode direct = schema.path("properties").path(fieldName);
		if (!direct.isMissingNode()) {
			return direct;
		}
		for (JsonNode branch : schema.path("allOf")) {
			JsonNode found = branch.path("properties").path(fieldName);
			if (!found.isMissingNode()) {
				return found;
			}
		}
		return objectMapper.missingNode();
	}

	/** OpenAPI 3.1 은 nullable 을 타입 배열(["object","null"])로 표현하므로 문자열·배열 둘 다 받는다. */
	private List<String> declaredTypes(JsonNode field) {
		JsonNode type = field.path("type");
		if (type.isArray()) {
			List<String> types = new ArrayList<>();
			type.forEach(node -> types.add(node.asString()));
			return types;
		}
		return type.isMissingNode() ? List.of() : List.of(type.asString());
	}
}

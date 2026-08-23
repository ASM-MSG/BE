package com.msg.fillmap.global.config;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI(Swagger) 문서 설정 (MSG-131). JWT Bearer 인증 스킴을 전역으로 걸어
 * Swagger UI 의 "Authorize" 버튼으로 토큰을 넣고 보호된 API 를 바로 호출할 수 있게 한다.
 */
@Configuration
public class OpenApiConfig {

	private static final String BEARER_SCHEME = "bearerAuth";

	/**
	 * 설명문이 "200 + data null" 을 약속하는 GET 엔드포인트 전수 (MSG-346).
	 * by-point 와 by-grid 는 같은 DTO 라 래퍼 스키마를 공유하지만, 경로 기준으로 선언해
	 * 어느 한쪽이 개명·제거돼도 목록이 문서 역할을 하게 한다.
	 */
	private static final List<String> NULLABLE_DATA_GET_PATHS = List.of(
		"/api/grids/{gridId}/cover",
		"/api/regions/reverse-geocode",
		"/api/regions/stats/by-point",
		"/api/regions/stats/by-grid");

	@Bean
	public OpenAPI fillmapOpenAPI() {
		return new OpenAPI()
			.info(new Info()
				.title("FillMap API")
				.description("FillMap API 문서")
				.version("v1"))
			.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
			.components(new Components()
				.addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")));
	}

	/**
	 * data 가 null 일 수 있는 응답의 스키마 정정 (MSG-346). 설명문은 "후보 없음/무귀속이면
	 * 200 + data null" 을 약속하는데 생성 스키마는 data 가 non-null 이라 FE 생성 타입이
	 * 런타임과 어긋나던 문제의 복구다.
	 *
	 * <p>이 프로젝트의 산출물은 OpenAPI 3.1(실측)이라 3.0 의 nullable 키워드가 직렬화에서
	 * 떨어진다. 원시 타입 필드의 {@code @Schema(nullable = true)} 가 {@code "type": ["string",
	 * "null"]} 로 나가는 것과 동치인 $ref 표현, 즉 {@code anyOf: [{$ref}, {type: "null"}]} 로
	 * data 속성을 감싼다.
	 */
	@Bean
	public OpenApiCustomizer nullableDataCustomizer() {
		return openApi -> NULLABLE_DATA_GET_PATHS.forEach(path -> markDataNullable(openApi, path));
	}

	private void markDataNullable(OpenAPI openApi, String path) {
		PathItem item = openApi.getPaths() == null ? null : openApi.getPaths().get(path);
		if (item == null || item.getGet() == null) {
			return; // 경로 개명 시 조용히 낡지 않도록 통합 테스트(OpenApiNullableDataTest)가 전수 검증한다
		}
		ApiResponse ok = item.getGet().getResponses() == null ? null : item.getGet().getResponses().get("200");
		if (ok == null || ok.getContent() == null) {
			return;
		}
		for (MediaType mediaType : ok.getContent().values()) {
			Schema<?> responseSchema = mediaType.getSchema();
			if (responseSchema == null || responseSchema.get$ref() == null) {
				continue;
			}
			String name = responseSchema.get$ref().substring(responseSchema.get$ref().lastIndexOf('/') + 1);
			Schema<?> wrapper = openApi.getComponents().getSchemas().get(name);
			if (wrapper == null || wrapper.getProperties() == null) {
				continue;
			}
			markNullable(wrapper.getProperties());
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void markNullable(Map<String, Schema> properties) {
		Schema data = properties.get("data");
		if (data == null || data.getAnyOf() != null) {
			return; // 같은 래퍼를 공유하는 형제 경로(by-point·by-grid)가 먼저 처리했으면 멱등
		}
		if (data.get$ref() == null) {
			return; // 현재 대상 전수가 $ref DTO 다. 원시 data 경로가 생기면 가드 테스트가 깨져 이 분기를 넓히게 된다
		}
		properties.put("data", anyOfNull(data));
	}

	/**
	 * {@code @Schema(nullable = true)} 가 붙은 $ref 속성의 표기 정정 (MSG-460). OpenAPI 3.1 산출물에서
	 * 이런 속성은 {@code {"$ref": ..., "type": "null"}} 로 나가는데, $ref 에 형제 키가 붙으면
	 * "그 DTO 이면서 동시에 null" 이라는 뜻이라 FE 코드 생성기가 타입을 null 로만 좁힌다. 원시 타입의
	 * {@code "type": ["string", "null"]} 과 동치인 {@code anyOf: [{$ref}, {type: "null"}]} 로 바꾼다.
	 *
	 * <p>필드를 열거하지 않고 컴포넌트 스키마 전수를 훑는다 — 같은 표기가 나오는 자리는 앞으로도
	 * 늘어나므로, 새 nullable $ref 필드가 생기면 애노테이션만으로 올바른 스키마가 나온다.
	 */
	@Bean
	public OpenApiCustomizer nullableRefPropertyCustomizer() {
		return openApi -> {
			if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
				return;
			}
			openApi.getComponents().getSchemas().values().forEach(this::unwrapNullableRefs);
		};
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void unwrapNullableRefs(Schema<?> schema) {
		Map<String, Schema> properties = schema.getProperties();
		if (properties == null) {
			return;
		}
		properties.replaceAll((name, property) -> isNullableRef(property) ? anyOfNull(property) : property);
	}

	@SuppressWarnings("rawtypes")
	private boolean isNullableRef(Schema property) {
		return property.get$ref() != null && property.getTypes() != null && property.getTypes().contains("null");
	}

	/** $ref 와 설명은 살리고 형제 {@code type} 은 anyOf 분기로 옮긴 스키마. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private Schema anyOfNull(Schema property) {
		Schema nullType = new Schema<>();
		nullType.addType("null");
		Schema wrapped = new Schema<>();
		wrapped.addAnyOfItem(new Schema<>().$ref(property.get$ref()));
		wrapped.addAnyOfItem(nullType);
		wrapped.setDescription(property.getDescription());
		return wrapped;
	}
}

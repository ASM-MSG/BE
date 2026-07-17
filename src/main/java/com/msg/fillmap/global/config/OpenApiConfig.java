package com.msg.fillmap.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI(Swagger) 문서 설정 (MSG-131). JWT Bearer 인증 스킴을 전역으로 걸어
 * Swagger UI 의 "Authorize" 버튼으로 토큰을 넣고 보호된 API 를 바로 호출할 수 있게 한다.
 */
@Configuration
public class OpenApiConfig {

	private static final String BEARER_SCHEME = "bearerAuth";

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
}

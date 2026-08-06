package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

/**
 * 응답 스키마 계약 가드 (MSG-319). 응답 DTO 의 모든 필드는 **항상 있음(required)** 이거나
 * **null 이 올 수 있음(nullable)** 이거나 둘 중 하나를 Swagger 스키마에 선언해야 한다.
 *
 * <p>둘 다 없으면 클라이언트는 그 필드를 어떻게 다뤄야 할지 문서만 봐서는 알 수 없다. 설명 문구에
 * "미획득이면 null" 같은 한국어 안내가 있어도 스키마를 읽는 코드 생성기·타입 정의는 그걸 못 읽어서,
 * 결국 사람이 백엔드에 물어보게 된다 — 실제로 반복된 문의가 이 테스트를 만든 이유다.
 *
 * <p>검사 대상은 `*ResponseDto` 와 그 안에 중첩된 record 다. 요청 DTO 는 대상이 아니다 —
 * `@NotNull`·`@NotBlank` 로 springdoc 이 required 를 자동 도출하고, 값을 안 보내도 되는 필드는
 * 아무것도 안 붙이는 게 정확한 명세이기 때문이다.
 *
 * <p>애플리케이션 컨텍스트도 springdoc 실행도 필요 없다(리플렉션 전용) — DB·Redis 없이 CI 에서 돈다.
 */
@DisplayName("응답 스키마 필드 선언 계약")
class ResponseSchemaNullabilityTest {

	private static final String BASE_PACKAGE = "com.msg.fillmap";
	private static final Pattern RESPONSE_DTO = Pattern.compile(".*ResponseDto");

	@Test
	@DisplayName("응답 DTO 의 모든 필드는 required 또는 nullable 중 하나를 선언한다")
	void 응답_DTO_필드는_required_이거나_nullable_이다() {
		List<String> undeclared = new ArrayList<>();
		for (Class<?> dto : findResponseDtos()) {
			collectUndeclared(dto, undeclared);
		}

		assertThat(undeclared)
			.as("""
				아래 필드는 항상 있는지(required) null 이 올 수 있는지(nullable) 선언돼 있지 않다.
				클래스 레벨 @Schema(requiredProperties = {"필드명"}) 또는
				필드 레벨 @Schema(nullable = true) 중 실제에 맞는 쪽을 붙여라.
				설명 문구에 한국어로만 적는 것은 스키마에 나가지 않으므로 인정되지 않는다.""")
			.isEmpty();
	}

	/** 응답 DTO 와 그 안에 중첩된 record 를 훑는다 — 중첩 항목 타입(예: 목록의 원소)도 클라이언트가 보는 스키마다. */
	private void collectUndeclared(Class<?> type, List<String> sink) {
		if (!type.isRecord()) {
			return;
		}
		Set<String> required = requiredProperties(type);
		for (RecordComponent component : type.getRecordComponents()) {
			if (!required.contains(component.getName()) && !isDeclaredOnComponent(type, component)) {
				sink.add(type.getSimpleName() + "::" + component.getName());
			}
		}
		for (Class<?> nested : type.getDeclaredClasses()) {
			collectUndeclared(nested, sink);
		}
	}

	private Set<String> requiredProperties(Class<?> type) {
		Schema schema = type.getAnnotation(Schema.class);
		return schema == null ? Set.of() : Set.of(schema.requiredProperties());
	}

	/**
	 * 필드 레벨 선언 확인. 애노테이션은 record component 가 아니라 **필드**에서 읽는다 — `@Schema` 는
	 * RECORD_COMPONENT 를 @Target 에 두지 않아 `RecordComponent#getAnnotation` 이 항상 null 을 준다.
	 */
	private boolean isDeclaredOnComponent(Class<?> type, RecordComponent component) {
		try {
			Field field = type.getDeclaredField(component.getName());
			Schema schema = field.getAnnotation(Schema.class);
			return schema != null
				&& (schema.nullable() || schema.requiredMode() == Schema.RequiredMode.REQUIRED);
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException("record component 에 대응하는 필드가 없다: " + component, e);
		}
	}

	private List<Class<?>> findResponseDtos() {
		// useDefaultFilters=false — 응답 DTO 는 스프링 빈이 아니라서 기본 필터(@Component 계열)로는 안 잡힌다.
		ClassPathScanningCandidateComponentProvider scanner =
			new ClassPathScanningCandidateComponentProvider(false) {
				@Override
				protected boolean isCandidateComponent(
					org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
					return true;   // 추상·비독립 제외 기본 규칙을 쓰지 않는다 — record 면 되고 판정은 호출부가 한다
				}
			};
		scanner.addIncludeFilter(new RegexPatternTypeFilter(RESPONSE_DTO));

		List<Class<?>> found = scanner.findCandidateComponents(BASE_PACKAGE).stream()
			.map(BeanDefinition::getBeanClassName)
			.map(ResponseSchemaNullabilityTest::load)
			.filter(Class::isRecord)
			.toList();

		assertThat(found)
			.as("응답 DTO 스캔이 0건이면 이 테스트는 아무것도 검사하지 못한다 — 패키지·필터 확인 필요")
			.isNotEmpty();
		return found;
	}

	private static Class<?> load(String className) {
		try {
			return Class.forName(className);
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("스캔된 클래스를 로드하지 못했다: " + className, e);
		}
	}

	/** 스캔이 실제로 프로젝트 전 도메인을 훑는지 — 한 도메인만 잡혀도 통과하는 무력한 그린을 막는다. */
	@Test
	@DisplayName("스캔이 여러 도메인의 응답 DTO 를 모두 잡는다")
	void 스캔은_여러_도메인을_훑는다() {
		List<String> packages = findResponseDtos().stream()
			.map(type -> type.getPackageName().replace(BASE_PACKAGE + ".", "").split("\\.")[0])
			.distinct()
			.toList();

		assertThat(packages).contains("badge", "friend", "notification", "video");
		assertThat(Arrays.stream(new String[] {"grid", "region", "zone"}).allMatch(packages::contains)).isTrue();
	}
}

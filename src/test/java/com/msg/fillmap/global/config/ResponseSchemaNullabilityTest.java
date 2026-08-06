package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

/**
 * 응답 스키마 계약 가드 (MSG-319). 응답 DTO 의 모든 필드는 `requiredProperties` 에 들어 있어야 한다.
 *
 * <p><b>왜 전부 required 인가</b> — 이 프로젝트는 Jackson 기본 직렬화를 쓴다(`@JsonInclude(NON_NULL)`
 * 전역·개별 설정 없음). 값이 null 이어도 <b>키는 항상 응답에 나간다</b>. OpenAPI 의 `required` 는
 * "값이 null 이 아니다"가 아니라 <b>"키가 존재한다"</b>는 뜻이므로, 응답 필드는 예외 없이 required 다.
 * 레포의 기존 계약과 같다 — `ApiResponseDto`(data 키 상존) · `UserProfileResponseDto`(email 은
 * required + nullable, MSG-310·311).
 *
 * <p><b>nullable 은 이 테스트가 강제하지 않는다</b> — required(키 존재)와 nullable(값이 null 일 수
 * 있음)은 직교하는 축이고, 어떤 필드가 null 을 낼 수 있는지는 코드를 아는 사람만 판단할 수 있어
 * 기계가 검증할 수 없다. 다만 값이 null 일 수 있으면 `@Schema(nullable = true)` 를 <b>함께</b>
 * 붙여야 한다. 둘 중 하나만 고르는 것이 아니다 — 초판에서 이 둘을 배타로 다뤘다가 Codex 리뷰에서
 * 잡혔고, 그 실수가 남으면 클라이언트가 "없을 수도 있는 키"로 오해한다.
 *
 * <p>검사 대상은 `*ResponseDto` 를 진입점으로 <b>필드 타입을 따라간 모든 타입</b>이다. 중첩 record,
 * 리스트 원소 타입, sealed 인터페이스의 구현체까지 훑는다 — `MissionResponseDto` → `MissionShape`
 * → `RegionShape` 처럼 이름이 `*ResponseDto` 가 아닌 타입도 클라이언트가 보는 스키마이기 때문이다.
 * record 가 아닌 클래스도 본다 — 모든 응답을 감싸는 `ApiResponseDto` 가 Lombok 클래스라
 * record 만 보면 정작 제일 중요한 스키마가 빠진다(Codex 리뷰 반영).
 * 요청 DTO 는 대상이 아니다 — `@NotNull`·`@NotBlank` 로 springdoc 이 required 를 자동 도출하고,
 * 안 보내도 되는 필드는 아무것도 안 붙이는 게 정확한 명세다.
 *
 * <p>애플리케이션 컨텍스트도 springdoc 실행도 필요 없다(리플렉션 전용) — DB·Redis 없이 CI 에서 돈다.
 */
@DisplayName("응답 스키마 필드 선언 계약")
class ResponseSchemaNullabilityTest {

	private static final String BASE_PACKAGE = "com.msg.fillmap";
	private static final Pattern RESPONSE_DTO = Pattern.compile(".*ResponseDto");

	@Test
	@DisplayName("응답 DTO 의 모든 필드는 required 로 선언된다 (Jackson 이 null 키도 내보내므로)")
	void 응답_DTO_필드는_전부_required_다() {
		List<String> undeclared = new ArrayList<>();
		Set<Class<?>> visited = new LinkedHashSet<>();
		for (Class<?> dto : findResponseDtos()) {
			collectUndeclared(dto, visited, undeclared);
		}

		assertThat(undeclared)
			.as("""
				아래 필드가 @Schema(requiredProperties) 에 빠져 있다.
				이 프로젝트는 Jackson 기본 직렬화라 값이 null 이어도 키는 항상 나간다 —
				required 는 "값이 non-null" 이 아니라 "키가 존재한다"는 뜻이므로 응답 필드는 전부 required 다.
				값이 null 일 수 있는 필드라면 @Schema(nullable = true) 를 '함께' 붙여라 (둘 중 택일 아님).""")
			.isEmpty();
	}

	/** 타입의 인스턴스 필드를 훑고, 필드 타입을 따라 참조된 타입·sealed 구현체까지 재귀한다. */
	private void collectUndeclared(Class<?> type, Set<Class<?>> visited, List<String> sink) {
		if (type == null || !type.getName().startsWith(BASE_PACKAGE) || !visited.add(type)) {
			return;
		}
		if (type.isSealed()) {
			for (Class<?> permitted : type.getPermittedSubclasses()) {
				collectUndeclared(permitted, visited, sink);
			}
		}
		for (Class<?> nested : type.getDeclaredClasses()) {
			collectUndeclared(nested, visited, sink);
		}
		if (type.isEnum()) {
			return;   // enum 은 값 목록이지 속성 집합이 아니다 — 상수 필드를 속성으로 오인하지 않는다
		}
		if (!type.isRecord() && type.getAnnotation(Schema.class) == null) {
			// 응답으로 나가는 클래스는 레포 관례상 클래스 레벨 @Schema 를 단다. 그게 없는 비-record 는
			// 와이어 스키마가 아니다 — Lombok 이 생성하는 ApiResponseDtoBuilder 같은 내부 헬퍼가 여기 걸린다.
			return;
		}
		// record 든 일반 클래스든 인스턴스 필드가 곧 직렬화되는 속성이다. record 만 보면
		// ApiResponseDto(모든 응답을 감싸는 Lombok 클래스)가 통째로 빠진다 (Codex 리뷰 반영).
		Set<String> required = requiredProperties(type);
		Set<String> fieldNames = new LinkedHashSet<>();
		for (Field field : type.getDeclaredFields()) {
			if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			fieldNames.add(field.getName());
			if (!required.contains(field.getName()) && !isRequiredOnField(field)) {
				sink.add(type.getSimpleName() + "::" + field.getName());
			}
			for (Class<?> referenced : referencedTypes(field)) {
				collectUndeclared(referenced, visited, sink);
			}
		}
		// 역방향 검사 — requiredProperties 에만 있고 실제 필드가 없는 이름은 유령 스키마다.
		// 필드 리네임 뒤 낡은 이름이 남으면 새 이름 누락은 위에서 잡혀도 유령은 조용히 스키마에
		// required 로 계속 나간다 (Codex 리뷰 반영).
		for (String name : required) {
			if (!fieldNames.contains(name)) {
				sink.add(type.getSimpleName() + "::" + name + " (requiredProperties 오타/잔재 — 실제 필드 없음)");
			}
		}
	}

	private Set<String> requiredProperties(Class<?> type) {
		Schema schema = type.getAnnotation(Schema.class);
		return schema == null ? Set.of() : Set.of(schema.requiredProperties());
	}

	/**
	 * 필드 레벨 `requiredMode = REQUIRED` 도 인정한다. record 라도 애노테이션은 <b>필드</b>에서 읽는다 —
	 * `@Schema` 는 RECORD_COMPONENT 를 @Target 에 두지 않아 `RecordComponent#getAnnotation` 은 항상 null 이다.
	 */
	private boolean isRequiredOnField(Field field) {
		Schema schema = field.getAnnotation(Schema.class);
		return schema != null && schema.requiredMode() == Schema.RequiredMode.REQUIRED;
	}

	/** 필드 타입 자체 + 제네릭 인자(List&lt;Spot&gt; 의 Spot 등)를 모두 후보로 낸다. */
	private List<Class<?>> referencedTypes(Field field) {
		List<Class<?>> types = new ArrayList<>();
		types.add(field.getType());
		collectGenericArguments(field.getGenericType(), types);
		return types;
	}

	private void collectGenericArguments(Type type, List<Class<?>> sink) {
		if (!(type instanceof ParameterizedType parameterized)) {
			return;
		}
		for (Type argument : parameterized.getActualTypeArguments()) {
			if (argument instanceof Class<?> clazz) {
				sink.add(clazz);
			}
			collectGenericArguments(argument, sink);
		}
	}

	private List<Class<?>> findResponseDtos() {
		// useDefaultFilters=false — 응답 DTO 는 스프링 빈이 아니라서 기본 필터(@Component 계열)로는 안 잡힌다.
		ClassPathScanningCandidateComponentProvider scanner =
			new ClassPathScanningCandidateComponentProvider(false) {
				@Override
				protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
					return true;   // 추상·비독립 제외 기본 규칙을 쓰지 않는다 — 판정은 호출부가 한다
				}
			};
		scanner.addIncludeFilter(new RegexPatternTypeFilter(RESPONSE_DTO));

		List<Class<?>> found = scanner.findCandidateComponents(BASE_PACKAGE).stream()
			.map(BeanDefinition::getBeanClassName)
			.map(ResponseSchemaNullabilityTest::load)
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

	/**
	 * 스캔이 전 도메인과 <b>참조 타입</b>까지 닿는지 — 한 도메인만 잡히거나 진입점만 훑어도 통과하는
	 * 무력한 그린을 막는다. `MissionShape` 계열은 이름이 `*ResponseDto` 가 아니라 참조를 따라가야만 닿는다.
	 */
	@Test
	@DisplayName("스캔이 여러 도메인과 참조 타입까지 훑는다")
	void 스캔은_참조_타입까지_훑는다() {
		Set<Class<?>> visited = new LinkedHashSet<>();
		List<String> sink = new ArrayList<>();
		for (Class<?> dto : findResponseDtos()) {
			collectUndeclared(dto, visited, sink);
		}

		List<String> names = visited.stream().map(Class::getSimpleName).toList();
		assertThat(names).contains("MissionShape", "RegionShape", "CategoryPreferenceDto", "ApiResponseDto");

		List<String> domains = visited.stream()
			.map(type -> type.getPackageName().replace(BASE_PACKAGE + ".", "").split("\\.")[0])
			.distinct()
			.toList();
		assertThat(domains).contains("badge", "friend", "notification", "video", "grid", "region", "zone");
	}
}

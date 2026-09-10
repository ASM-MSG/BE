package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * 시각 타입 계약 가드 (MSG-376). 전역 JSON 처리기({@link UtcLocalDateTimeJsonCodec})는 {@code LocalDateTime}
 * 한 타입만 UTC {@code Z} 표기로 다룬다. 그래서 새 DTO 가 {@code Instant}·{@code OffsetDateTime}·
 * {@code java.util.Date} 같은 다른 시각 타입을 쓰면 그 필드만 조용히 다른 표기로 나간다 — 이 테스트가 그때 깨진다.
 *
 * <p>{@code LocalDate} 는 허용한다. 시각이 아니라 날짜 라벨이고({@code "2026-08-11"}), 시간대 해석 여지가
 * 없어 이 계약이 막으려는 문제와 무관하다 (업로드 기록의 KST 날짜, MSG-362).
 *
 * <p>한계: 시각을 {@code String} 에 담는 우회와 컬렉션 원소 타입은 정적 검사로 못 걸러 리뷰 몫이다.
 */
@DisplayName("DTO 시각 타입 계약")
class DtoTimeTypeGuardTest {

	private static final String BASE_PACKAGE = "com.msg.fillmap";

	@Test
	void DTO의_시각_타입은_LocalDateTime뿐이다() {
		List<String> violations = new ArrayList<>();
		Set<Class<?>> dtoClasses = scanDtoClasses();

		for (Class<?> dtoClass : dtoClasses) {
			for (Field field : dtoClass.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
					continue;
				}
				Class<?> type = field.getType();
				if (isTimeType(type) && type != LocalDateTime.class && type != LocalDate.class) {
					violations.add(dtoClass.getSimpleName() + "." + field.getName() + " : " + type.getName());
				}
			}
		}

		// 스캐너가 아무것도 못 잡으면 이 테스트는 통과해도 아무것도 지키지 못한다 — 표본 수부터 확인한다.
		assertThat(dtoClasses).hasSizeGreaterThan(50);
		assertThat(violations)
			.as("시각 필드는 LocalDateTime 이어야 전역 UTC 표기 계약(MSG-376)이 적용된다")
			.isEmpty();
	}

	/** record 는 기본 후보 판정에서 걸러질 수 있어 판정을 열어 둔다 — 빈 등록이 아니라 타입만 훑는 용도라 무해하다. */
	private Set<Class<?>> scanDtoClasses() {
		ClassPathScanningCandidateComponentProvider scanner =
			new ClassPathScanningCandidateComponentProvider(false) {
				@Override
				protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
					return true;
				}
			};
		scanner.addIncludeFilter((metadataReader, factory) ->
			metadataReader.getClassMetadata().getClassName().endsWith("Dto"));

		Set<Class<?>> classes = new LinkedHashSet<>();
		for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
			try {
				classes.add(Class.forName(definition.getBeanClassName()));
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException("스캔된 DTO 클래스를 로드하지 못했다: " + definition.getBeanClassName(), e);
			}
		}
		return classes;
	}

	private boolean isTimeType(Class<?> type) {
		String name = type.getName();
		return name.startsWith("java.time.")
			|| name.equals("java.util.Date")
			|| name.equals("java.util.Calendar")
			|| name.equals("java.sql.Timestamp");
	}
}

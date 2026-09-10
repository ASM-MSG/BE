package com.msg.fillmap.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

/**
 * OSIV 비활성 가드 (MSG-587). {@code spring.jpa.open-in-view} 는 스프링 기본값이 {@code true} 라
 * 설정을 지우기만 해도 조용히 다시 켜진다 — 그러면 응답 직렬화 시점에 지연 로딩 쿼리가 나가고,
 * 쿼리가 어디서 몇 번 나가는지 추적이 끊긴다. 이 테스트가 그때 깨진다.
 *
 * <p>기동 로그의 {@code open-in-view is enabled by default} 경고로는 이 계약을 지킬 수 없다.
 * 그 경고는 <b>값을 명시하지 않았을 때만</b> 뜨므로 누군가 {@code true} 로 바꿔 켜면 경고 없이 켜진다
 * (2026-09-10 실측: 설정을 지우고 돌려도 테스트 로그에는 그 WARN 이 찍히지 않았다). 그래서 값을 직접 읽는다.
 *
 * <p>인터셉터 빈 부재도 함께 본다 — 프로퍼티는 값일 뿐이고, 실제로 요청 동안 영속성 컨텍스트를 붙들어 두는
 * 주체가 {@link OpenEntityManagerInViewInterceptor} 다. 둘이 어긋나면(프로퍼티는 false 인데 다른 설정이
 * 인터셉터를 등록하는 경우) 값만 보는 단언은 통과해 버린다.
 */
@SpringBootTest
@DisplayName("OSIV 비활성 계약")
class OsivDisabledGuardTest {

	@Autowired
	private Environment environment;

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	@DisplayName("spring.jpa.open-in-view는_false다")
	void spring_jpa_open_in_view는_false다() {
		assertThat(environment.getProperty("spring.jpa.open-in-view"))
			.as("OSIV 를 다시 켜지 말 것 — project-conventions 영속 계층 7항")
			.isEqualTo("false");
	}

	@Test
	@DisplayName("요청_동안_영속성_컨텍스트를_붙드는_인터셉터가_없다")
	void 요청_동안_영속성_컨텍스트를_붙드는_인터셉터가_없다() {
		assertThat(applicationContext.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class))
			.as("OpenEntityManagerInViewInterceptor 가 등록되면 프로퍼티와 무관하게 OSIV 가 동작한다")
			.isEmpty();
	}
}

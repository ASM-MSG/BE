package com.msg.fillmap.event.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;

/**
 * 기동 진입점의 트랜잭션 계약 (MSG-438). 다른 시더 테스트는 {@code seed()} 를 직접 부르고 트랜잭션을
 * 테스트가 열기 때문에, {@code run()} 의 {@code @Transactional} 이 사라져도 전부 통과한다.
 * 여기서는 스프링이 프록시로 감싼 실제 빈의 {@code run()} 을 부르고 테스트는 트랜잭션을 열지 않는다.
 * <p>
 * 경계가 사라지면 리포지토리 save 마다 자체 트랜잭션으로 커밋돼 앞부분이 DB 에 남고, 같은 이유로
 * 트랜잭션 수명에 묶인 advisory lock 도 문장 단위로 풀려 시더 간 직렬화가 무너진다. 후반 실패 시
 * 앞 행이 하나도 남지 않는다는 것이 그 경계의 관측 가능한 증거다.
 */
@SpringBootTest
@DisplayName("EventSeeder 기동 진입점 트랜잭션 계약 (실 PostgreSQL) — 프록시 경유 run 호출")
class EventSeederRunnerTest {

	@Autowired
	private EventSeeder eventSeeder;

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventLocationRepository locationRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("시드 후반이 실패하면 앞서 쓴 행까지 전부 롤백된다")
	void 시드_후반이_실패하면_앞서_쓴_행까지_전부_롤백된다() {
		// 싱글턴 빈의 설정을 잠시 바꾼다. 되돌릴 값은 고정 기본값이 아니라 지금 실제로 들어 있는 값이다
		// (프로파일이나 다른 설정으로 기본값과 달라져 있어도 원래 상태 그대로 복구되게).
		Object 이전_enabled = ReflectionTestUtils.getField(eventSeeder, "enabled");
		Object 이전_path = ReflectionTestUtils.getField(eventSeeder, "path");
		try {
			ReflectionTestUtils.setField(eventSeeder, "enabled", true);
			ReflectionTestUtils.setField(eventSeeder, "path", "seed/msg438-runner-events.json");

			// 파일의 두 번째 회차 마지막 위치가 역전된 사각형이라, 첫 회차와 그 위치·격자가 쓰인 뒤에 깨진다.
			assertThatThrownBy(() -> eventSeeder.run(new DefaultApplicationArguments()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("역전");

			assertThat(seriesRepository.findBySeriesKey("msg438-runner")).isEmpty();
			assertThat(occurrenceRepository.findByOccurrenceKey("msg438-runner-ok")).isEmpty();
			assertThat(locationRepository.findByLocationKey("msg438-runner-ok-loc")).isEmpty();
		} finally {
			ReflectionTestUtils.setField(eventSeeder, "enabled", 이전_enabled);
			ReflectionTestUtils.setField(eventSeeder, "path", 이전_path);
			정리();
		}
	}

	/** 계약이 깨진 경우에 대비한 방어 정리 (공유 로컬 DB). 정상 경로에서는 지울 행이 없다. */
	private void 정리() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			List<String> statements = List.of(
				"""
					DELETE FROM event_location_grids WHERE event_location_id IN
					(SELECT id FROM event_locations WHERE location_key LIKE 'msg438-runner%')""",
				"DELETE FROM event_locations WHERE location_key LIKE 'msg438-runner%'",
				"DELETE FROM event_occurrences WHERE occurrence_key LIKE 'msg438-runner%'",
				"DELETE FROM event_series WHERE series_key LIKE 'msg438-runner%'");
			statements.forEach(sql -> em.createNativeQuery(sql).executeUpdate());
		});
	}
}

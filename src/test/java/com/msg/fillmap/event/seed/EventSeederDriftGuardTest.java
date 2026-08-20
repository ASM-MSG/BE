package com.msg.fillmap.event.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventVideo;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 대표 격자 드리프트 가드 (MSG-438). 영상이 붙은 위치의 대표 격자가 바뀌면 기존 영상은 옛 격자에, 신규 영상은
 * 새 격자에 붙어 위치의 피드와 격자 노출이 갈라진다 — 시더가 그 재시드를 거부하는지 본다.
 * <p>
 * 격리(공유 로컬 DB): 합성 자연키(msg438-drift/race-*)와 서해 먼바다 합성 좌표만 쓴다. 동시성 테스트만
 * 실제 커밋이 필요해 NOT_SUPPORTED + TransactionTemplate 로 돌리고 @AfterEach 에서 명시 정리한다.
 */
@SpringBootTest
@DisplayName("EventSeeder 대표 격자 드리프트 가드 (실 PostgreSQL)")
class EventSeederDriftGuardTest {

	/** 서해 먼바다 합성 좌표 (FestivalMissionSeederIntegrationTest 선례) — 육상 실데이터와 격자를 공유하지 않는다. */
	private static final double 합성_LAT = 34.0;
	private static final double 합성_LON = 125.0;

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventLocationRepository locationRepository;

	@Autowired
	private EventLocationGridRepository locationGridRepository;

	@Autowired
	private EventVideoRepository eventVideoRepository;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private EntityManager em;

	private EventSeeder seeder() {
		EventSeeder seeder = new EventSeeder(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository, eventVideoRepository, objectMapper);
		ReflectionTestUtils.setField(seeder, "enabled", true);
		ReflectionTestUtils.setField(seeder, "path", "unused");
		return seeder;
	}

	/** 대표 격자가 영상의 격자와 같아지도록 3×3 영역을 중심 격자에 맞춘다. */
	private Resource json(String prefix, int centerGridY, int centerGridX) {
		String content = """
			[{"seriesKey":"%1$s","seriesName":"%1$s 시리즈","occurrences":[
				{"occurrenceKey":"%1$s-2026","title":"%1$s 행사","cityName":"부산",
				 "startsAt":"2026-10-06T10:00:00+09:00","endsAt":"2026-10-15T22:00:00+09:00",
				 "exposure":{"minGridY":%2$d,"maxGridY":%3$d,"minGridX":%4$d,"maxGridX":%5$d},
				 "locations":[
					{"locationKey":"%1$s-loc","name":"%1$s 장소","type":"ETC","operatingHours":null,
					 "displayOrder":1,"representativeGridId":null,
					 "areaRects":[{"minGridY":%6$d,"maxGridY":%7$d,"minGridX":%8$d,"maxGridX":%9$d}]}]}]}]"""
			.formatted(prefix, centerGridY - 50, centerGridY + 50, centerGridX - 50, centerGridX + 50,
				centerGridY - 1, centerGridY + 1, centerGridX - 1, centerGridX + 1);
		return new ByteArrayResource(content.getBytes());
	}

	private Long 사용자생성() {
		return userRepository.save(User.createLocalUser(
			"msg438-drift-" + UUID.randomUUID() + "@example.com", "hash", "드리프트")).getId();
	}

	private Long 영상생성(Long userId, String gridId) {
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		Video video = Video.create(userId, gridId, "videos/original/" + UUID.randomUUID() + ".mp4",
			GeoSupport.toPoint(합성_LAT, 합성_LON), (short) 10, LocalDateTime.now(ZoneOffset.UTC), Visibility.PUBLIC);
		return videoRepository.save(video).getId();
	}

	private void 행사영상연결(Long videoId, EventLocation location) {
		eventVideoRepository.save(new EventVideo(videoRepository.findById(videoId).orElseThrow(),
			location, location.getOccurrence().getId()));
	}

	@Test
	@Transactional
	@DisplayName("영상이 있는 위치의 대표 격자가 바뀌는 재시드는 실패한다")
	void 영상이_있는_위치의_대표_격자가_바뀌는_재시드는_실패한다() {
		String gridId = GridEncoder.encode(합성_LAT, 합성_LON);
		GridIndex 중심 = GridEncoder.decode(gridId);
		seeder().seed(json("msg438-drift", (int) 중심.gridY(), (int) 중심.gridX()));
		EventLocation location = locationRepository.findByLocationKey("msg438-drift-loc").orElseThrow();
		assertThat(location.getRepresentativeGridId()).isEqualTo(gridId);
		행사영상연결(영상생성(사용자생성(), gridId), location);
		em.flush();

		// 영역을 북쪽으로 10칸 밀면 대표 격자도 함께 움직인다 — 영상이 붙어 있으므로 거부돼야 한다.
		Resource 이동 = json("msg438-drift", (int) 중심.gridY() + 10, (int) 중심.gridX());
		assertThatThrownBy(() -> seeder().seed(이동))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("이미 영상이 있어 대표 격자를");
	}

	@Test
	@Transactional
	@DisplayName("영상이 있어도 대표 격자가 그대로면 재시드가 성공한다 (가드는 변경만 막는다)")
	void 영상이_있어도_대표_격자가_그대로면_재시드가_성공한다() {
		String gridId = GridEncoder.encode(합성_LAT, 합성_LON);
		GridIndex 중심 = GridEncoder.decode(gridId);
		seeder().seed(json("msg438-nodrift", (int) 중심.gridY(), (int) 중심.gridX()));
		EventLocation location = locationRepository.findByLocationKey("msg438-nodrift-loc").orElseThrow();
		행사영상연결(영상생성(사용자생성(), gridId), location);
		em.flush();

		seeder().seed(json("msg438-nodrift", (int) 중심.gridY(), (int) 중심.gridX()));

		em.flush();
		em.clear();
		assertThat(locationRepository.findByLocationKey("msg438-nodrift-loc").orElseThrow()
			.getRepresentativeGridId()).isEqualTo(gridId);
	}

	/**
	 * 시더의 event_locations 행 잠금 계약 검증. 업로드(MSG-440 경로)가 같은 행을 먼저 잠그고 영상을 붙이는
	 * 도중에 시더가 대표 격자를 바꾸려 하면, 시더는 잠금 대기 후 커밋된 영상을 보고 거부해야 한다.
	 * 시더가 잠금 없이 읽으면 READ COMMITTED 스냅숏이 미커밋 영상을 못 봐 가드를 그냥 통과한다 —
	 * 그 경우 이 테스트가 깨진다.
	 */
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("대표 격자 변경 재시드와 행사 업로드가 경쟁해도 드리프트가 없다")
	void 대표_격자_변경_재시드와_행사_업로드가_경쟁해도_드리프트가_없다() throws InterruptedException {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		String gridId = GridEncoder.encode(합성_LAT, 합성_LON);
		GridIndex 중심 = GridEncoder.decode(gridId);

		tx.executeWithoutResult(status -> seeder().seed(json("msg438-race", (int) 중심.gridY(), (int) 중심.gridX())));
		Long videoId = tx.execute(status -> 영상생성(사용자생성(), gridId));

		AtomicReference<Exception> 시더예외 = new AtomicReference<>();
		AtomicReference<Integer> 시더_pid = new AtomicReference<>();
		Thread 시더 = new Thread(() -> {
			try {
				new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
					시더_pid.set(백엔드_pid());   // 잠금 대기에 들어가기 전에 자기 세션을 알린다
					seeder().seed(json("msg438-race", (int) 중심.gridY() + 10, (int) 중심.gridX()));
				});
			} catch (Exception e) {
				시더예외.set(e);
			}
		}, "msg438-race-seeder");

		Boolean 시더가_대기했다 = tx.execute(status -> {
			// 업로드 경로가 대표 격자를 읽기 위해 같은 행을 잠근다 (MSG-440 계약).
			EventLocation location = locationRepository.findWithLockByLocationKey("msg438-race-loc").orElseThrow();
			행사영상연결(videoId, location);
			eventVideoRepository.flush();
			시더.start();
			return 내가_막고_있기를_기다린다(시더_pid);
		});
		시더.join(15_000);

		// 대기를 확인하고 커밋했다는 것이 잠금 계약의 관측 증거다. 시간만 기다리면 시더가 늦게 시작한
		// 경우에도 통과해 버리므로, 경쟁 지점 도달을 실제로 확인한다.
		assertThat(시더가_대기했다).as("시더가 위치 행 잠금에서 실제로 대기해야 한다").isTrue();
		assertThat(시더예외.get())
			.as("잠금 뒤 커밋된 영상을 보고 대표 격자 변경을 거부해야 한다")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("이미 영상이 있어 대표 격자를");
		// 시더 트랜잭션이 롤백돼 영상의 격자와 위치의 대표 격자가 여전히 같다 (드리프트 없음).
		String 최종_대표 = tx.execute(status -> locationRepository.findByLocationKey("msg438-race-loc").orElseThrow()
			.getRepresentativeGridId());
		assertThat(최종_대표).isEqualTo(gridId);
	}

	/**
	 * 반대 직렬화 순서. 시더가 먼저 위치 행을 잠그고 대표 격자를 옮기는 동안 업로드가 들어오면, 업로드는
	 * 대기 후 <b>갱신된</b> 대표 격자를 읽어야 한다. 낡은 값을 읽으면 신규 영상이 옛 격자에 붙어
	 * 위치의 피드와 격자 노출이 갈라진다 — 앞 테스트와 반대 방향의 같은 드리프트다.
	 */
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("시더가 먼저 잠그면 업로드는 갱신된 대표 격자를 읽는다 (반대 직렬화 순서)")
	void 시더가_먼저_잠그면_업로드는_갱신된_대표_격자를_읽는다() throws InterruptedException {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		String gridId = GridEncoder.encode(합성_LAT, 합성_LON);
		GridIndex 중심 = GridEncoder.decode(gridId);
		tx.executeWithoutResult(status -> seeder().seed(json("msg438-race2", (int) 중심.gridY(), (int) 중심.gridX())));

		AtomicReference<String> 업로드가_읽은_대표 = new AtomicReference<>();
		AtomicReference<Integer> 업로드_pid = new AtomicReference<>();
		Thread 업로드 = new Thread(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			업로드_pid.set(백엔드_pid());
			업로드가_읽은_대표.set(locationRepository.findWithLockByLocationKey("msg438-race2-loc").orElseThrow()
				.getRepresentativeGridId());
		}), "msg438-race2-upload");

		Boolean 업로드가_대기했다 = tx.execute(status -> {
			// 영상이 붙어 있지 않으므로 가드를 통과하고 대표 격자가 북쪽 10칸으로 이동한다.
			seeder().seed(json("msg438-race2", (int) 중심.gridY() + 10, (int) 중심.gridX()));
			업로드.start();
			return 내가_막고_있기를_기다린다(업로드_pid);
		});
		업로드.join(15_000);

		assertThat(업로드가_대기했다).as("업로드가 시더의 위치 행 잠금에서 실제로 대기해야 한다").isTrue();
		assertThat(업로드가_읽은_대표)
			.as("커밋된 새 대표 격자를 읽어야 한다 (낡은 값 %s 가 아니다)", gridId)
			.hasValue(((int) 중심.gridY() + 10) + "_" + (int) 중심.gridX());
	}

	/**
	 * 상대 스레드가 <b>이 세션 때문에</b> 막힐 때까지 기다린다 (최대 10초, 관찰되면 true).
	 * {@code pg_blocking_pids} 는 그 세션을 실제로 막고 있는 백엔드 목록이라, 여기에 내 pid 가 있으면
	 * "내가 잡은 잠금이 상대를 세우고 있다"가 확정된다. 전역 미허용 잠금 수를 세면 무관한 세션의 대기나
	 * 다른 잠금(예: 시더끼리의 advisory lock) 대기를 경쟁 지점 도달로 오인한다.
	 */
	private boolean 내가_막고_있기를_기다린다(AtomicReference<Integer> 상대_pid) {
		for (int attempt = 0; attempt < 200; attempt++) {
			Integer pid = 상대_pid.get();
			if (pid != null && 내가_막고_있는가(pid)) {
				return true;
			}
			sleep();
		}
		return false;
	}

	private boolean 내가_막고_있는가(int 상대_pid) {
		return (Boolean) em.createNativeQuery(
				"SELECT pg_backend_pid() = ANY(pg_blocking_pids(:pid))")
			.setParameter("pid", 상대_pid)
			.getSingleResult();
	}

	private int 백엔드_pid() {
		return ((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
	}

	private void sleep() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 동시성 테스트만 실제로 커밋하므로 합성 행을 명시적으로 지운다 (공유 로컬 DB). */
	@AfterEach
	void 정리() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			List<String> statements = List.of(
				"""
					DELETE FROM event_videos WHERE event_location_id IN
					(SELECT id FROM event_locations WHERE location_key LIKE 'msg438-race%')""",
				"""
					DELETE FROM event_location_grids WHERE event_location_id IN
					(SELECT id FROM event_locations WHERE location_key LIKE 'msg438-race%')""",
				"DELETE FROM event_locations WHERE location_key LIKE 'msg438-race%'",
				"DELETE FROM event_occurrences WHERE occurrence_key LIKE 'msg438-race%'",
				"DELETE FROM event_series WHERE series_key LIKE 'msg438-race%'",
				"DELETE FROM videos WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'msg438-drift-%')",
				"DELETE FROM users WHERE email LIKE 'msg438-drift-%'");
			statements.forEach(sql -> em.createNativeQuery(sql).executeUpdate());
		});
	}
}

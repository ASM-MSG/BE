package com.msg.fillmap.event.service;

import static com.msg.fillmap.video.support.S3VideoObjectStub.givenUploadedVideoObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.services.s3.S3Client;

import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.event.EventTestFixtures;
import com.msg.fillmap.event.dto.EventVideoUploadRequestDto;
import com.msg.fillmap.event.dto.EventVideoUploadResponseDto;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.service.VideoEncodingService;
import com.msg.fillmap.video.service.VideoService;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

/**
 * 행사 업로드의 위치 행 잠금 계약 (MSG-440 §도메인 잠금 계약, MSG-438 동시성 계약의 업로드 측).
 * 업로드가 대표 격자를 읽기 전에 event_locations 행을 잠그고, 잠금 획득 후 회차를 다시 읽는지 본다 —
 * 둘 다 "잠금 대기 사이에 바뀐 값"이 관측 대상이라 실제 커밋과 두 커넥션이 필요하다.
 *
 * <p>결정적 인터리빙 프로토콜(EventSeederDriftGuardTest·MSG-243 재사용): 메인 트랜잭션이 위치 행을 잠근
 * 채 값을 바꾸고 → 업로드 스레드를 띄워 그 잠금에 블록시키고 → 제3의 관측(pg_blocking_pids)으로 실제
 * 대기를 확인한 뒤에야 커밋한다. 고정 sleep 이 없어 경쟁 지점 도달이 보장된다.
 *
 * <p>격리(공유 로컬 DB): 서해 먼바다 격자와 합성 자연키(msg440-race-*)만 쓰고, 실제 커밋을 하므로
 * {@code @AfterEach} 에서 FK 역순 대상 지정 삭제로 정리한다. 업로드의 부수 서비스(뱃지·스트릭·핫스코어·
 * 인코딩)와 S3 는 목이라 정리 대상이 videos·user_grids·행사 3층으로 좁혀진다.
 */
@SpringBootTest
@DisplayName("행사 업로드 동시성 — 위치 행 잠금과 회차 재독 (실 PostgreSQL)")
class EventVideoUploadConcurrencyTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	/** 서해 먼바다 기준 격자 — 다른 행사 테스트(125.0·125.2·125.3)와 겹치지 않는 대역. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.4));

	private static final int 관측_시도 = 200;
	private static final long 관측_간격_MS = 50L;
	private static final long JOIN_TIMEOUT_MS = 15_000L;

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
	private UserRepository userRepository;

	@Autowired
	private VideoService videoService;

	// 아래 셋은 피드·상세 경로의 협력자다 — 업로드는 쓰지 않지만 서비스 생성에 필요해 받아 둔다.
	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	@Autowired
	private ZoneNameQueryService zoneNameQueryService;

	/** 업로드는 반응을 읽지 않지만 상세·피드 경로의 협력자라 서비스 생성에 필요하다 (MSG-441). */
	@Autowired
	private EventVideoInteractionService interactionService;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private S3Client s3Client;

	@MockitoBean
	private VideoEncodingService videoEncodingService;

	@MockitoBean
	private HotScoreCommandService hotScoreCommandService;

	@MockitoBean
	private BadgeAwardService badgeAwardService;

	@MockitoBean
	private StreakCommandService streakCommandService;

	private TransactionTemplate tx;
	private EventTestFixtures fixtures;
	private Long userId;

	@BeforeEach
	void setUp() {
		givenUploadedVideoObject(s3Client);
		given(badgeAwardService.awardUploadBadges(anyLong())).willReturn(List.of());
		given(badgeAwardService.awardCollectionBadges(anyLong(), anyString())).willReturn(List.of());
		given(streakCommandService.recordUpload(anyLong())).willReturn(List.of());
		tx = new TransactionTemplate(transactionManager);
		fixtures = new EventTestFixtures(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository);
		userId = tx.execute(status -> userRepository.save(User.createLocalUser(
			"msg440-race-" + UUID.randomUUID() + "@example.com", "hash", "경쟁업로더")).getId());
	}

	/** 실제로 커밋하므로 합성 행을 FK 역순으로 대상 지정 삭제한다 (실데이터 불가침). */
	@AfterEach
	void 정리() {
		tx.executeWithoutResult(status -> List.of(
			"""
				DELETE FROM event_videos WHERE event_location_id IN
				(SELECT id FROM event_locations WHERE location_key LIKE 'msg440-race-%')""",
			"DELETE FROM user_grids WHERE user_id = " + userId,
			"DELETE FROM region_stats WHERE user_id = " + userId,
			"DELETE FROM videos WHERE user_id = " + userId,
			"""
				DELETE FROM event_location_grids WHERE event_location_id IN
				(SELECT id FROM event_locations WHERE location_key LIKE 'msg440-race-%')""",
			"DELETE FROM event_locations WHERE location_key LIKE 'msg440-race-%'",
			"DELETE FROM event_occurrences WHERE occurrence_key LIKE 'msg440-race-%'",
			"DELETE FROM event_series WHERE series_key LIKE 'msg440-race-%'",
			"DELETE FROM grids WHERE grid_id LIKE '" + 바다.gridY() + "_%'",
			"DELETE FROM users WHERE id = " + userId
		).forEach(sql -> em.createNativeQuery(sql).executeUpdate()));
	}

	private EventVideoService service() {
		return new EventVideoServiceImpl(occurrenceRepository, locationRepository, eventVideoRepository,
			videoService, videoRepository, thumbnailUrlPresigner, zoneNameQueryService, em, interactionService,
			Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	private String 격자(long dx) {
		return 바다.gridY() + "_" + (바다.gridX() + dx);
	}

	private EventVideoUploadRequestDto 요청() {
		return new EventVideoUploadRequestDto(
			"videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4", (short) 10, NOW);
	}

	/**
	 * 업로드 한 건을 자기 트랜잭션에서 돌리는 스레드. 트랜잭션 진입 직후 자기 백엔드 pid 를 알린다 —
	 * 이후 서비스 호출이 같은 커넥션을 쓰므로, 메인이 "내가 저 pid 를 막고 있는가"로 대기를 관측할 수 있다.
	 */
	private Thread 업로드_스레드(long occurrenceId, long locationId, AtomicReference<Integer> pid,
		AtomicReference<EventVideoUploadResponseDto> 응답, AtomicReference<Exception> 예외) {
		return new Thread(() -> {
			try {
				new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
					pid.set(백엔드_pid());
					응답.set(service().upload(userId, occurrenceId, locationId, 요청()));
				});
			} catch (Exception e) {
				예외.set(e);
			}
		}, "msg440-race-upload");
	}

	// 검증: FR-EVENT-08
	@Test
	@DisplayName("대표 격자 재계산 시더와 행사 업로드가 경쟁해도 드리프트가 없다")
	void 대표_격자_재계산_시더와_행사_업로드가_경쟁해도_드리프트가_없다() throws InterruptedException {
		Long[] ids = tx.execute(status -> {
			EventOccurrence occurrence = fixtures.회차(fixtures.시리즈(), NOW.minusDays(1), NOW.plusDays(1), 격자(0));
			EventLocation location = fixtures.위치(occurrence, "행사 위치", 격자(0), 격자(1));
			return new Long[] {occurrence.getId(), location.getId()};
		});
		AtomicReference<Integer> 업로드_pid = new AtomicReference<>();
		AtomicReference<EventVideoUploadResponseDto> 응답 = new AtomicReference<>();
		AtomicReference<Exception> 예외 = new AtomicReference<>();
		Thread 업로드 = 업로드_스레드(ids[0], ids[1], 업로드_pid, 응답, 예외);

		Boolean 업로드가_대기했다 = tx.execute(status -> {
			// 시더 측 이행: 같은 행을 잠그고 대표 격자를 옆 칸으로 옮긴다 (영역 안이라 지연 FK 통과).
			EventLocation locked = locationRepository.findWithLockById(ids[1]).orElseThrow();
			locked.update(locked.getOccurrence(), locked.getName(), locked.getType(), locked.getOperatingHours(),
				locked.getDisplayOrder(), 격자(1));
			locationRepository.flush();
			업로드.start();
			return 내가_막고_있기를_기다린다(업로드_pid);
		});
		업로드.join(JOIN_TIMEOUT_MS);

		assertThat(업로드가_대기했다).as("업로드가 위치 행 잠금에서 실제로 대기해야 한다").isTrue();
		assertThat(예외.get()).isNull();
		// 낡은 대표 격자에 붙었다면 위치의 피드와 격자 노출이 갈라진다 — 갱신된 값을 읽어야 한다.
		assertThat(응답.get().gridId()).isEqualTo(격자(1));
	}

	// 검증: FR-EVENT-10
	@Test
	@DisplayName("창 판정은 위치 잠금 후 재독한 회차 값으로 한다")
	void 창_판정은_위치_잠금_후_재독한_회차_값으로_한다() throws InterruptedException {
		Long[] ids = tx.execute(status -> {
			// 처음엔 진행 중이라 업로드가 열려 있다 — 업로드 스레드는 이 값을 잠금 전에 읽는다.
			EventOccurrence occurrence = fixtures.회차(fixtures.시리즈(), NOW.minusDays(1), NOW.plusDays(1), 격자(2));
			EventLocation location = fixtures.위치(occurrence, "행사 위치", 격자(2));
			return new Long[] {occurrence.getId(), location.getId()};
		});
		AtomicReference<Integer> 업로드_pid = new AtomicReference<>();
		AtomicReference<EventVideoUploadResponseDto> 응답 = new AtomicReference<>();
		AtomicReference<Exception> 예외 = new AtomicReference<>();
		Thread 업로드 = 업로드_스레드(ids[0], ids[1], 업로드_pid, 응답, 예외);

		Boolean 업로드가_대기했다 = tx.execute(status -> {
			// 리시드 이행: 위치 행을 잠근 채 회차 일정을 "아직 시작 전"으로 바꾼다.
			EventLocation locked = locationRepository.findWithLockById(ids[1]).orElseThrow();
			EventOccurrence occurrence = occurrenceRepository.findById(ids[0]).orElseThrow();
			occurrence.update(occurrence.getSeries(), occurrence.getTitle(), occurrence.getCityName(),
				NOW.plusDays(10), NOW.plusDays(11), occurrence.getMinGridY(), occurrence.getMaxGridY(),
				occurrence.getMinGridX(), occurrence.getMaxGridX());
			occurrenceRepository.flush();
			assertThat(locked.getId()).isEqualTo(ids[1]);
			업로드.start();
			return 내가_막고_있기를_기다린다(업로드_pid);
		});
		업로드.join(JOIN_TIMEOUT_MS);

		assertThat(업로드가_대기했다).as("업로드가 위치 행 잠금에서 실제로 대기해야 한다").isTrue();
		// 잠금 전에 읽은 낡은 회차(진행 중)로 판정하면 업로드가 성공해 버린다 — 재독하면 시작 전이라 13410.
		assertThat(응답.get()).isNull();
		assertThat(예외.get())
			.isInstanceOf(ApiException.class)
			.extracting(e -> ((ApiException) e).getErrorCode())
			.isEqualTo(EventErrorCode.EVENT_UPLOAD_NOT_STARTED);
	}

	/**
	 * 상대 세션이 <b>내가 잡은 잠금 때문에</b> 멈춰 있는지 확인한다. 전역 대기 수를 세면 무관한 세션의
	 * 대기를 경쟁 지점 도달로 오인하므로 pg_blocking_pids 로 좁힌다 (EventSeederDriftGuardTest 선례).
	 */
	private boolean 내가_막고_있기를_기다린다(AtomicReference<Integer> 상대_pid) {
		for (int attempt = 0; attempt < 관측_시도; attempt++) {
			Integer pid = 상대_pid.get();
			if (pid != null && 내가_막고_있는가(pid)) {
				return true;
			}
			sleep();
		}
		return false;
	}

	private boolean 내가_막고_있는가(int 상대_pid) {
		return (Boolean) em.createNativeQuery("SELECT pg_backend_pid() = ANY(pg_blocking_pids(:pid))")
			.setParameter("pid", 상대_pid)
			.getSingleResult();
	}

	private int 백엔드_pid() {
		return ((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
	}

	private void sleep() {
		try {
			Thread.sleep(관측_간격_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}

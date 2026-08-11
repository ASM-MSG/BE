package com.msg.fillmap.usergrid.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 날짜별 업로드 기록 조회 (MSG-362, 실 DB). 서비스 전 구간을 태워 저장 존 바인딩(§D4 — created_at 을
 * JVM 기본 존으로 해석 후 KST 변환)까지 검증한다. 각 테스트는 자기 유저를 새로 만들고 @Transactional 로
 * 롤백한다(UserGridRepositoryTest 패턴). 픽스처는 KST 벽시계로 쓰고 storedAt 이 저장 존 값으로 환산해
 * 넣는다 — JVM 존이 KST 가 아닌 환경에서도 같은 KST 날짜로 접혀야 한다.
 */
// 검증: FR-STREAK-08
@SpringBootTest
@Transactional
@DisplayName("날짜별 업로드 기록 조회 (실 DB)")
class UploadHistoryIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// 공해상(서해) 좌표 대역 — 다른 usergrid 테스트 블록(17413/7637, 17516/7535)과 겹치지 않게 격리.
	private static final long GY0 = 17425L;
	private static final long GX0 = 7645L;

	private static final LocalDate DAY1 = LocalDate.of(2026, 8, 8);
	private static final LocalDate DAY2 = LocalDate.of(2026, 8, 9);
	private static final LocalDate DAY3 = LocalDate.of(2026, 8, 10);

	@Autowired
	private UserGridQueryService userGridQueryService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("같은 날 여러 번 올리면 하루 한 항목으로 접히고 건수만 는다")
	void 같은_날_여러_번_올리면_하루_한_항목으로_접히고_건수만_는다() {
		long me = newUser();
		String gridId = grid(0);
		video(me, gridId, DAY1.atTime(9, 0), "ACTIVE");
		video(me, gridId, DAY1.atTime(12, 30), "ACTIVE");
		video(me, gridId, DAY1.atTime(21, 45), "ACTIVE");
		// 다른 사용자의 같은 날 업로드는 내 기록에 섞이지 않는다 — 사용자 격리.
		video(newUser(), gridId, DAY1.atTime(10, 0), "ACTIVE");

		assertThat(userGridQueryService.getUploadHistory(me))
			.containsExactly(new UploadHistoryView(DAY1, 3));
	}

	@Test
	@DisplayName("업로드가 없는 날은 응답에 항목이 없다")
	void 업로드가_없는_날은_응답에_항목이_없다() {
		long me = newUser();
		String gridId = grid(0);
		video(me, gridId, DAY1.atTime(9, 0), "ACTIVE");
		video(me, gridId, DAY3.atTime(9, 0), "ACTIVE");

		// FR-6: 사이의 빈 날(DAY2)은 항목 자체가 없다 — 잔디 빈 칸 채우기는 FE 몫.
		assertThat(userGridQueryService.getUploadHistory(me))
			.containsExactly(new UploadHistoryView(DAY1, 1), new UploadHistoryView(DAY3, 1));
	}

	@Test
	@DisplayName("기록의 날짜는 스트릭 판정과 같은 KST 경계로 접힌다")
	void 기록의_날짜는_스트릭_판정과_같은_KST_경계로_접힌다() {
		long me = newUser();
		String gridId = grid(0);
		// FR-7: KST 자정 직전과 직후 — 저장 존이 KST 가 아니면 벽시계 날짜가 다를 수 있는 구간이다.
		video(me, gridId, DAY1.atTime(23, 59, 30), "ACTIVE");
		video(me, gridId, DAY2.atTime(0, 0, 30), "ACTIVE");

		assertThat(userGridQueryService.getUploadHistory(me))
			.containsExactly(new UploadHistoryView(DAY1, 1), new UploadHistoryView(DAY2, 1));
	}

	@Test
	@DisplayName("삭제한 영상의 업로드 날짜도 기록에 남는다")
	void 삭제한_영상의_업로드_날짜도_기록에_남는다() {
		long me = newUser();
		String gridId = grid(0);
		video(me, gridId, DAY1.atTime(9, 0), "DELETED");
		video(me, gridId, DAY2.atTime(9, 0), "BLINDED");

		// FR-8: status 무필터가 의도 — 업로드 사실은 있었고 스트릭이 삭제로 차감되지 않는 것과 정합.
		assertThat(userGridQueryService.getUploadHistory(me))
			.containsExactly(new UploadHistoryView(DAY1, 1), new UploadHistoryView(DAY2, 1));
	}

	@Test
	@DisplayName("업로드가 없는 사용자는 빈 배열을 받는다")
	void 업로드가_없는_사용자는_빈_배열을_받는다() {
		assertThat(userGridQueryService.getUploadHistory(newUser())).isEmpty();
	}

	@Test
	@DisplayName("기록은 날짜 오름차순으로 정렬된다")
	void 기록은_날짜_오름차순으로_정렬된다() {
		long me = newUser();
		String gridId = grid(0);
		// 삽입 순서를 뒤섞어도 결과는 uploadDate 오름차순 고정.
		video(me, gridId, DAY3.atTime(9, 0), "ACTIVE");
		video(me, gridId, DAY1.atTime(9, 0), "ACTIVE");
		video(me, gridId, DAY2.atTime(9, 0), "ACTIVE");

		assertThat(userGridQueryService.getUploadHistory(me))
			.extracting(UploadHistoryView::uploadDate)
			.containsExactly(DAY1, DAY2, DAY3);
	}

	private long newUser() {
		String email = "history-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "기록테스터")).getId();
	}

	private String grid(long dx) {
		return GridFixtures.seedGrid(em, GY0, GX0 + dx);
	}

	/** KST 벽시계 시각을 저장 존(JVM 기본 존) 벽시계로 환산한다 — 쿼리의 이중 AT TIME ZONE 역방향. */
	private LocalDateTime storedAt(LocalDateTime kstWallClock) {
		return kstWallClock.atZone(KST).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
	}

	/** videos 삽입 — created_at 만 명시(KST 벽시계 입력을 저장 존으로 환산), 나머지는 요약 테스트 형상 그대로. */
	private void video(long userId, String gridId, LocalDateTime kstCreatedAt, String status) {
		em.createNativeQuery("""
			INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at, status, created_at)
			VALUES (
				:userId, :gridId,
				ST_SetSRID(ST_MakePoint(124.95, 35.75), 4326)::geography,
				10, now(), :status, :createdAt
			)
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("status", status)
			.setParameter("createdAt", storedAt(kstCreatedAt))
			.executeUpdate();
	}
}

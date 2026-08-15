package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;

import com.msg.fillmap.friend.dto.FriendListItemResponseDto;
import com.msg.fillmap.friend.dto.FriendProfileResponseDto;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.dto.UserProfileResponseDto;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 위치정보 사용 동의 저장 (MSG-402 FR-1~4·6·8, 실 DB). @Transactional 롤백 격리로 공유 로컬 DB 에
 * 시드를 남기지 않고, 사용자는 UUID 이메일로 만들어 실데이터·타 테스트와 부딪히지 않는다
 * (UserProfileIntegrationTest 패턴).
 *
 * <p>저장 값 확인은 영속성 컨텍스트를 비운 뒤 native 조회로 한다 — 갱신이 @Modifying 원자 UPDATE 라
 * 1차 캐시의 갱신 전 스냅숏과 어긋날 수 있는 지점이고, 엔티티 재조회만으로는 "DB 에 정말 그 값이
 * 들어갔나"를 못 본다.
 *
 * <p>동시 요청 직렬화(§D-4)는 여기서 검증하지 않는다 — 두 스레드가 서로의 커밋을 봐야 하므로
 * 롤백 격리를 포기하고 공유 로컬 DB 에 실제 행을 커밋해야 한다. 직렬화는 UPDATE 문 하나에 대한
 * PostgreSQL 의 행 잠금 보장이라 애플리케이션 코드가 아니라 DB 가 지키는 성질이고, 우리 쪽 계약인
 * "값이 달라질 때만 시각 갱신"은 아래 멱등 테스트가 문장 수준에서 덮는다.
 */
@SpringBootTest
@Transactional
@DisplayName("위치정보 사용 동의 저장 (실 DB)")
class UserLocationConsentIntegrationTest {

	@Autowired
	private UserService userService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	/** 실 S3 호출 차단용 — UserServiceImpl 이 주입받는 다른 축의 의존성, 이 테스트에선 미사용. */
	@MockitoBean
	private S3Client s3Client;

	private long userId;

	@BeforeEach
	void setUp() {
		String email = "consent-" + UUID.randomUUID() + "@example.com";
		userId = userRepository.save(User.createLocalUser(email, "hash", "채우미")).getId();
	}

	/** DB 실저장 값 — [location_consent, location_consent_changed_at]. 1차 캐시를 비우고 읽는다. */
	private Object[] storedConsent(long id) {
		em.flush();
		em.clear();
		return (Object[]) em.createNativeQuery(
				"SELECT location_consent, location_consent_changed_at FROM users WHERE id = :id")
			.setParameter("id", id).getSingleResult();
	}

	private boolean storedFlag(long id) {
		return (Boolean) storedConsent(id)[0];
	}

	private LocalDateTime storedChangedAt(long id) {
		return (LocalDateTime) storedConsent(id)[1];
	}

	/**
	 * 고정 시각 클럭을 문 서비스. 동의 경로가 쓰는 협력자는 리포지토리 하나뿐이라 나머지는 넘기지 않는다.
	 * 트랜잭션은 이 테스트 클래스가 이미 열어 뒀으므로 프록시 없는 직접 호출로도 @Modifying 쿼리가 실행된다.
	 */
	private UserService serviceAt(LocalDateTime utc) {
		return new UserServiceImpl(userRepository, null, null, null, null, null,
			Clock.fixed(utc.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	@Nested
	@DisplayName("기본값")
	class Defaults {

		// 검증: FR-USER-14
		@Test
		@DisplayName("신규 가입 사용자는 위치정보 미동의로 시작한다 (FR-7·8)")
		void 신규_가입_사용자는_위치정보_미동의로_시작한다() {
			UserProfileResponseDto profile = userService.getMyProfile(userId);

			assertThat(profile.locationConsent()).isFalse();
			assertThat(storedChangedAt(userId)).isNull();
		}

		// 검증: FR-USER-14
		@Test
		@DisplayName("동의 컬럼 없이 INSERT 한 행은 V32 DEFAULT 로 false · NULL 이 된다 (FR-8)")
		void 동의_컬럼_없이_INSERT_한_행은_V32_DEFAULT로_false에_NULL이_된다() {
			// 검증 대상은 DEFAULT 절이다 — V31 시점 행에 ALTER 가 어떤 값을 채웠는지가 아니다(그 하네스는
			// 과하다). 동의 컬럼을 지정하지 않는 INSERT 는 V32 이전 코드가 내보내던 문장과 같은 모양이라,
			// 애플리케이션이 값을 안 넣어도 미동의로 시작한다는 성질을 같은 DEFAULT 절로 확인한다.
			long legacyId = ((Number) em.createNativeQuery("""
				INSERT INTO users (provider, oid, email, nickname, friend_code)
				VALUES ('KAKAO', :oid, NULL, '기존유저', :code)
				RETURNING id
				""")
				.setParameter("oid", "oid-" + UUID.randomUUID())
				.setParameter("code", UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase())
				.getSingleResult()).longValue();

			assertThat(storedFlag(legacyId)).isFalse();
			assertThat(storedChangedAt(legacyId)).isNull();
		}
	}

	@Nested
	@DisplayName("동의 변경")
	class Update {

		// 검증: FR-USER-14
		@Test
		@DisplayName("동의를 켜면 응답과 DB 와 후속 프로필 조회에 모두 반영된다 (FR-2)")
		void 동의를_켜면_응답과_DB와_후속_프로필_조회에_모두_반영된다() {
			UserProfileResponseDto updated = userService.updateLocationConsent(userId, true);

			assertThat(updated.locationConsent()).isTrue();
			assertThat(updated.nickname()).isEqualTo("채우미");   // 두 컬럼만 만지므로 다른 필드는 그대로다 (§D-8)
			assertThat(storedFlag(userId)).isTrue();
			assertThat(userService.getMyProfile(userId).locationConsent()).isTrue();
		}

		// 검증: FR-USER-14
		@Test
		@DisplayName("동의를 끄면 응답과 DB 와 후속 프로필 조회에 모두 반영된다 (FR-2, 양방향 전환)")
		void 동의를_끄면_응답과_DB와_후속_프로필_조회에_모두_반영된다() {
			userService.updateLocationConsent(userId, true);

			UserProfileResponseDto updated = userService.updateLocationConsent(userId, false);

			assertThat(updated.locationConsent()).isFalse();
			assertThat(storedFlag(userId)).isFalse();
			assertThat(userService.getMyProfile(userId).locationConsent()).isFalse();
		}

		// 검증: FR-USER-14
		@Test
		@DisplayName("동의를 켜면 변경 시각이 UTC 로 저장된다 (FR-3·D-5, 고정 클럭)")
		void 동의를_켜면_변경_시각이_UTC로_저장된다() {
			LocalDateTime fixedUtc = LocalDateTime.of(2026, 8, 15, 3, 24, 11);

			serviceAt(fixedUtc).updateLocationConsent(userId, true);

			// KST 벽시계가 새어 들어오면 9시간 어긋나 이 단언이 깨진다 (시각 처리 컨벤션, MSG-376).
			assertThat(storedChangedAt(userId)).isEqualTo(fixedUtc);
		}

		// 검증: FR-USER-14
		@Test
		@DisplayName("철회하면 변경 시각이 동의 시각에서 새 값으로 교체된다 (FR-3, 고정 클럭 2개)")
		void 철회하면_변경_시각이_동의_시각에서_새_값으로_교체된다() {
			LocalDateTime consentedAt = LocalDateTime.of(2026, 8, 15, 3, 24, 11);
			LocalDateTime revokedAt = LocalDateTime.of(2026, 8, 16, 9, 0, 0);
			serviceAt(consentedAt).updateLocationConsent(userId, true);

			serviceAt(revokedAt).updateLocationConsent(userId, false);

			// 값이 남아 있기만 한 게 아니라 철회 시각으로 바뀌어야 한다 — 갱신 누락이면 동의 시각이 그대로 남는다.
			assertThat(storedChangedAt(userId)).isEqualTo(revokedAt).isNotEqualTo(consentedAt);
		}

		// 검증: FR-USER-14
		@Test
		@DisplayName("동의 변경 시각은 지금 UTC 벽시계와 같은 축이다 (프로덕션 클럭 경로)")
		void 동의_변경_시각은_지금_UTC_벽시계와_같은_축이다() {
			// 프로덕션 빈은 Clock.systemUTC() 를 고정해 주입받는다 — KST 벽시계였다면 9시간 뒤라 창을 벗어난다.
			LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

			userService.updateLocationConsent(userId, true);

			assertThat(storedChangedAt(userId)).isBetween(nowUtc.minusMinutes(1), nowUtc.plusMinutes(1));
		}
	}

	@Nested
	@DisplayName("같은 값 재저장 (멱등)")
	class Idempotent {

		// 검증: FR-USER-14
		@Test
		@DisplayName("동의 상태 true 를 true 로 재저장해도 성공하고 변경 시각은 그대로다 (FR-4)")
		void 동의_상태_true를_true로_재저장해도_성공하고_변경_시각은_그대로다() {
			userService.updateLocationConsent(userId, true);
			LocalDateTime firstChangedAt = storedChangedAt(userId);

			UserProfileResponseDto again = userService.updateLocationConsent(userId, true);

			assertThat(again.locationConsent()).isTrue();
			assertThat(storedChangedAt(userId)).isEqualTo(firstChangedAt);
		}

		// 검증: FR-USER-14
		@Test
		@DisplayName("동의 상태 false 를 false 로 재저장해도 성공하고 변경 시각은 그대로다 (FR-4)")
		void 동의_상태_false를_false로_재저장해도_성공하고_변경_시각은_그대로다() {
			// 한 번도 바꾼 적 없는 사용자라 시각이 NULL 이다 — 재저장이 여기에 값을 채우면 안 된다.
			UserProfileResponseDto again = userService.updateLocationConsent(userId, false);

			assertThat(again.locationConsent()).isFalse();
			assertThat(storedChangedAt(userId)).isNull();
		}

		// 검증: FR-USER-14
		@Test
		@DisplayName("철회 후 같은 값 재저장도 시각을 건드리지 않는다 (FR-4, NULL 아닌 시각)")
		void 철회_후_같은_값_재저장도_시각을_건드리지_않는다() {
			userService.updateLocationConsent(userId, true);
			userService.updateLocationConsent(userId, false);
			LocalDateTime revokedAt = storedChangedAt(userId);

			userService.updateLocationConsent(userId, false);

			assertThat(revokedAt).isNotNull();
			assertThat(storedChangedAt(userId)).isEqualTo(revokedAt);
		}
	}

	@Nested
	@DisplayName("실패 · 노출 경계")
	class Boundaries {

		@Test
		@DisplayName("없는 사용자의 동의 변경은 USER_NOT_FOUND 다 (1404, 영향 행 0)")
		void 없는_사용자의_동의_변경_요청은_1404다() {
			assertThatThrownBy(() -> userService.updateLocationConsent(-1L, true))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
		}

		// 검증: FR-USER-14
		@Test
		@DisplayName("친구 축 응답에는 동의 여부가 실리지 않는다 (FR-6, 본인 전용)")
		void 친구_프로필_응답에는_동의_여부가_실리지_않는다() {
			assertThat(componentNames(FriendProfileResponseDto.class)).doesNotContain("locationConsent");
			assertThat(componentNames(FriendListItemResponseDto.class)).doesNotContain("locationConsent");
		}

		private String[] componentNames(Class<?> record) {
			return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new);
		}
	}
}

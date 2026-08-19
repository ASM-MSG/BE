package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.dto.ConsentStatusResponseDto;
import com.msg.fillmap.user.dto.ConsentSubmitRequestDto;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 가입 약관 동의 저장과 게이트 판별 (MSG-433 FR-1~9, 실 DB). @Transactional 롤백 격리로 공유 로컬 DB 에
 * 시드를 남기지 않고, 사용자는 UUID 이메일로 만들어 실데이터·타 테스트와 부딪히지 않는다
 * (UserLocationConsentIntegrationTest 패턴 그대로).
 *
 * <p>저장 값 확인은 영속성 컨텍스트를 비운 뒤 native 조회로 한다 — 갱신이 @Modifying 원자 UPDATE 라
 * 1차 캐시의 갱신 전 스냅숏과 어긋날 수 있는 지점이고, 엔티티 재조회만으로는 COALESCE·IS DISTINCT FROM
 * 이 DB 에서 실제로 무엇을 남겼는지 못 본다.
 *
 * <p>필수 4항목 미충족 거절(FR-3)은 Bean Validation 이라 서비스에 닿지 않는다 — 컨트롤러 테스트가 덮는다.
 */
@SpringBootTest
@Transactional
@DisplayName("가입 약관 동의 저장 (실 DB)")
class UserConsentIntegrationTest {

	private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 8, 19, 3, 24, 11);
	private static final LocalDateTime LATER = LocalDateTime.of(2026, 8, 20, 9, 0, 0);
	private static final LocalDateTime MUCH_LATER = LocalDateTime.of(2026, 8, 21, 12, 30, 0);

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
		String email = "terms-consent-" + UUID.randomUUID() + "@example.com";
		userId = userRepository.save(User.createLocalUser(email, "hash", "채우미")).getId();
	}

	/** 필수 4항목 동의 + 마케팅만 인자로 받는 제출 요청. */
	private static ConsentSubmitRequestDto submit(boolean marketing) {
		return new ConsentSubmitRequestDto(true, true, true, true, marketing);
	}

	/**
	 * 고정 시각 클럭을 문 서비스. 동의 경로가 쓰는 협력자는 리포지토리 하나뿐이라 나머지는 넘기지 않는다.
	 * 트랜잭션은 이 테스트 클래스가 이미 열어 뒀으므로 프록시 없는 직접 호출로도 @Modifying 쿼리가 실행된다.
	 */
	private UserService serviceAt(LocalDateTime utc) {
		return new UserServiceImpl(userRepository, null, null, null, null, null,
			Clock.fixed(utc.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	/** DB 실저장 시각·플래그 7종. 1차 캐시를 비우고 읽는다. */
	private Object[] stored() {
		em.flush();
		em.clear();
		return (Object[]) em.createNativeQuery("""
				SELECT age_over14_consented_at, service_terms_consented_at, privacy_consented_at,
				       location_consent, location_consent_changed_at,
				       marketing_consent, marketing_consent_changed_at
				FROM users WHERE id = :id
				""")
			.setParameter("id", userId).getSingleResult();
	}

	private LocalDateTime storedAgeOver14At() {
		return (LocalDateTime) stored()[0];
	}

	private LocalDateTime storedLocationChangedAt() {
		return (LocalDateTime) stored()[4];
	}

	private LocalDateTime storedMarketingChangedAt() {
		return (LocalDateTime) stored()[6];
	}

	@Nested
	@DisplayName("도입 직후 상태")
	class Initial {

		// 검증: FR-USER-15
		@Test
		@DisplayName("기존 사용자는 필수 동의 미완료 상태로 조회된다 (FR-7, 백필 없음)")
		void 기존_사용자는_필수_동의_미완료_상태로_조회된다() {
			ConsentStatusResponseDto status = userService.getConsentStatus(userId);

			assertThat(status.ageOver14()).isFalse();
			assertThat(status.serviceTerms()).isFalse();
			assertThat(status.privacyPolicy()).isFalse();
			assertThat(status.locationTerms()).isFalse();
			assertThat(status.marketing()).isFalse();
			assertThat(status.requiredCompleted()).isFalse();
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("동의 컬럼 없이 INSERT 한 행도 V37 DEFAULT 로 미동의로 시작한다 (FR-7)")
		void 동의_컬럼_없이_INSERT_한_행도_미동의로_시작한다() {
			// 동의 컬럼을 지정하지 않는 INSERT 는 V37 이전 코드가 내보내던 문장과 같은 모양이다 —
			// 애플리케이션이 값을 안 넣어도 미동의로 시작한다는 성질을 DEFAULT 절로 확인한다 (V32 선례).
			long legacyId = ((Number) em.createNativeQuery("""
				INSERT INTO users (provider, oid, email, nickname, friend_code)
				VALUES ('KAKAO', :oid, NULL, '기존유저', :code)
				RETURNING id
				""")
				.setParameter("oid", "oid-" + UUID.randomUUID())
				.setParameter("code", UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase())
				.getSingleResult()).longValue();

			assertThat(userService.getConsentStatus(legacyId).requiredCompleted()).isFalse();
			assertThat(userService.getConsentStatus(legacyId).marketing()).isFalse();
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("미동의 사용자도 다른 API 가 그대로 동작한다 (FR-9, 서버 차단 없음)")
		void 미동의_사용자도_다른_API가_그대로_동작한다() {
			assertThat(userService.getConsentStatus(userId).requiredCompleted()).isFalse();

			assertThat(userService.getMyProfile(userId).nickname()).isEqualTo("채우미");
			assertThat(userService.updateNickname(userId, "새닉네임").nickname()).isEqualTo("새닉네임");
		}
	}

	@Nested
	@DisplayName("동의 제출")
	class Submit {

		// 검증: FR-USER-15
		@Test
		@DisplayName("동의 제출 후 상태 조회에 5항목과 필수 완료가 반영된다 (FR-1·2)")
		void 동의_제출_후_상태_조회에_5항목과_필수_완료가_반영된다() {
			ConsentStatusResponseDto submitted = userService.submitConsents(userId, submit(true));

			assertThat(submitted.requiredCompleted()).isTrue();
			assertThat(submitted.marketing()).isTrue();

			ConsentStatusResponseDto reread = userService.getConsentStatus(userId);
			assertThat(reread.ageOver14()).isTrue();
			assertThat(reread.serviceTerms()).isTrue();
			assertThat(reread.privacyPolicy()).isTrue();
			assertThat(reread.locationTerms()).isTrue();
			assertThat(reread.marketing()).isTrue();
			assertThat(reread.requiredCompleted()).isTrue();
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("동의 제출 시 필수 3항목의 동의 시각이 UTC 로 저장된다 (FR-4, 고정 클럭)")
		void 동의_제출_시_필수_3항목의_동의_시각이_UTC로_저장된다() {
			serviceAt(SUBMITTED_AT).submitConsents(userId, submit(false));

			// KST 벽시계가 새어 들어오면 9시간 어긋나 이 단언이 깨진다 (시각 처리 컨벤션, MSG-376).
			Object[] row = stored();
			assertThat((LocalDateTime) row[0]).isEqualTo(SUBMITTED_AT);
			assertThat((LocalDateTime) row[1]).isEqualTo(SUBMITTED_AT);
			assertThat((LocalDateTime) row[2]).isEqualTo(SUBMITTED_AT);
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("동의 제출이 위치 동의를 켜고 변경 시각을 남긴다 (FR-5, 단일 값)")
		void 동의_제출이_위치_동의를_켜고_변경_시각을_남긴다() {
			serviceAt(SUBMITTED_AT).submitConsents(userId, submit(false));

			Object[] row = stored();
			assertThat((Boolean) row[3]).isTrue();
			assertThat((LocalDateTime) row[4]).isEqualTo(SUBMITTED_AT);
			// 같은 한 값이므로 프로필 응답의 위치 동의도 함께 켜진다.
			assertThat(userService.getMyProfile(userId).locationConsent()).isTrue();
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("이미 위치 동의가 켜진 사용자의 제출은 위치 변경 시각을 덮지 않는다 (FR-5, MSG-402 선행 경로)")
		void 이미_위치_동의가_켜진_사용자의_제출은_위치_변경_시각을_덮지_않는다() {
			serviceAt(SUBMITTED_AT).updateLocationConsent(userId, true);

			serviceAt(LATER).submitConsents(userId, submit(false));

			assertThat(storedLocationChangedAt()).isEqualTo(SUBMITTED_AT);
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("마케팅 false 제출은 성공하고 마케팅만 미동의로 남는다 (선택 항목)")
		void 마케팅_false_제출은_성공하고_마케팅만_미동의로_남는다() {
			ConsentStatusResponseDto status = userService.submitConsents(userId, submit(false));

			assertThat(status.requiredCompleted()).isTrue();
			assertThat(status.marketing()).isFalse();
			// 값이 기본값 false 에서 달라지지 않았으므로 변경 시각도 남지 않는다 (IS DISTINCT FROM 경로).
			assertThat(storedMarketingChangedAt()).isNull();
		}

		@Test
		@DisplayName("없는 사용자의 제출은 USER_NOT_FOUND 다 (1404, 영향 행 0)")
		void 없는_사용자의_제출은_1404다() {
			assertThatThrownBy(() -> userService.submitConsents(-1L, submit(true)))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
		}
	}

	@Nested
	@DisplayName("재제출 (멱등)")
	class Idempotent {

		// 검증: FR-USER-15
		@Test
		@DisplayName("같은 내용 재제출은 성공하고 필수 3항목의 최초 동의 시각이 그대로다 (FR-8, COALESCE)")
		void 같은_내용_재제출은_성공하고_필수_3항목의_최초_동의_시각이_그대로다() {
			serviceAt(SUBMITTED_AT).submitConsents(userId, submit(true));

			ConsentStatusResponseDto again = serviceAt(LATER).submitConsents(userId, submit(true));

			assertThat(again.requiredCompleted()).isTrue();
			Object[] row = stored();
			assertThat((LocalDateTime) row[0]).isEqualTo(SUBMITTED_AT);
			assertThat((LocalDateTime) row[1]).isEqualTo(SUBMITTED_AT);
			assertThat((LocalDateTime) row[2]).isEqualTo(SUBMITTED_AT);
			// 위치·마케팅도 같은 값 재제출이라 마지막 변경 시각이 유지된다 (IS DISTINCT FROM 경로).
			assertThat((LocalDateTime) row[4]).isEqualTo(SUBMITTED_AT);
			assertThat((LocalDateTime) row[6]).isEqualTo(SUBMITTED_AT);
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("재제출에서 마케팅 값이 바뀌면 마케팅 변경 시각만 갱신된다")
		void 재제출에서_마케팅_값이_바뀌면_마케팅_변경_시각만_갱신된다() {
			serviceAt(SUBMITTED_AT).submitConsents(userId, submit(true));

			serviceAt(LATER).submitConsents(userId, submit(false));

			Object[] row = stored();
			assertThat((Boolean) row[5]).isFalse();
			assertThat((LocalDateTime) row[6]).isEqualTo(LATER);
			assertThat((LocalDateTime) row[0]).isEqualTo(SUBMITTED_AT);   // 필수 3항목은 불변
			assertThat((LocalDateTime) row[4]).isEqualTo(SUBMITTED_AT);   // 위치는 이미 true 라 불변
		}
	}

	@Nested
	@DisplayName("마케팅 동의 변경")
	class Marketing {

		// 검증: FR-USER-15
		@Test
		@DisplayName("마케팅 변경 API 로 켜고 끄면 상태와 변경 시각이 반영된다 (FR-6)")
		void 마케팅_변경_API로_켜고_끄면_상태와_변경_시각이_반영된다() {
			ConsentStatusResponseDto on = serviceAt(SUBMITTED_AT).updateMarketingConsent(userId, true);
			assertThat(on.marketing()).isTrue();
			assertThat(storedMarketingChangedAt()).isEqualTo(SUBMITTED_AT);

			ConsentStatusResponseDto off = serviceAt(LATER).updateMarketingConsent(userId, false);
			assertThat(off.marketing()).isFalse();
			assertThat(storedMarketingChangedAt()).isEqualTo(LATER);
			// 선택 항목이라 필수 완료 판정에는 영향이 없다 (아직 제출 전이라 false).
			assertThat(off.requiredCompleted()).isFalse();
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("마케팅 같은 값 재저장은 성공하고 변경 시각이 그대로다 (멱등)")
		void 마케팅_같은_값_재저장은_성공하고_변경_시각이_그대로다() {
			serviceAt(SUBMITTED_AT).updateMarketingConsent(userId, true);

			ConsentStatusResponseDto again = serviceAt(LATER).updateMarketingConsent(userId, true);

			assertThat(again.marketing()).isTrue();
			assertThat(storedMarketingChangedAt()).isEqualTo(SUBMITTED_AT);
		}

		@Test
		@DisplayName("없는 사용자의 마케팅 변경은 USER_NOT_FOUND 다 (1404, 영향 행 0)")
		void 없는_사용자의_마케팅_변경은_1404다() {
			assertThatThrownBy(() -> userService.updateMarketingConsent(-1L, true))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
		}
	}

	@Nested
	@DisplayName("위치 동의와의 접점")
	class LocationLink {

		// 검증: FR-USER-15
		@Test
		@DisplayName("위치 철회 시도는 1400 으로 거절되고 필수 완료가 true 로 유지된다 (§D-11, 철회 불가)")
		void 위치_철회_시도는_거절되고_필수_완료가_유지된다() {
			userService.submitConsents(userId, submit(true));

			assertThatThrownBy(() -> userService.updateLocationConsent(userId, false))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.LOCATION_CONSENT_IRREVOCABLE);

			ConsentStatusResponseDto status = userService.getConsentStatus(userId);
			assertThat(status.locationTerms()).isTrue();
			assertThat(status.requiredCompleted()).isTrue();
			// 필수 4항목 전부 철회 경로가 없어 동의를 마친 사용자에게 게이트가 다시 뜨지 않는다.
			assertThat(status.ageOver14()).isTrue();
			assertThat(status.serviceTerms()).isTrue();
			assertThat(status.privacyPolicy()).isTrue();
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("위치만 동의했던 기존 사용자는 철회 거절과 재제출까지 게이트 생명주기가 이어진다 (FR-7 · §D-11)")
		void 위치만_동의했던_기존_사용자는_철회_거절과_재제출까지_게이트_생명주기가_이어진다() {
			// MSG-402 온보딩만 거친 기존 사용자 — location_consent 는 true 인데 신규 필수 시각 3개는 NULL 이다.
			serviceAt(SUBMITTED_AT).updateLocationConsent(userId, true);
			assertThat(storedAgeOver14At()).isNull();

			// ① 위치가 켜져 있어도 필수 동의는 미완료다.
			assertThat(userService.getConsentStatus(userId).locationTerms()).isTrue();
			assertThat(userService.getConsentStatus(userId).requiredCompleted()).isFalse();

			// ② 게이트 제출로 완료가 된다. 이미 켜진 위치의 변경 시각은 덮이지 않는다.
			assertThat(serviceAt(LATER).submitConsents(userId, submit(false)).requiredCompleted()).isTrue();
			assertThat(storedLocationChangedAt()).isEqualTo(SUBMITTED_AT);

			// ③ 프로필 화면에서 위치를 철회하려 해도 1400 으로 거절돼 완료 상태가 유지된다 (§D-11).
			assertThatThrownBy(() -> serviceAt(MUCH_LATER).updateLocationConsent(userId, false))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.LOCATION_CONSENT_IRREVOCABLE);
			assertThat(userService.getConsentStatus(userId).requiredCompleted()).isTrue();
			assertThat(storedLocationChangedAt()).isEqualTo(SUBMITTED_AT);

			// ④ 게이트 재제출도 그대로 성공하고(멱등) 시각은 어느 것도 갱신되지 않는다.
			LocalDateTime resubmittedAt = MUCH_LATER.plusHours(1);
			assertThat(serviceAt(resubmittedAt).submitConsents(userId, submit(false)).requiredCompleted()).isTrue();
			assertThat(storedLocationChangedAt()).isEqualTo(SUBMITTED_AT);
			// 필수 3항목의 최초 동의 시각은 ② 시점 그대로다 (COALESCE).
			assertThat(storedAgeOver14At()).isEqualTo(LATER);
		}
	}
}

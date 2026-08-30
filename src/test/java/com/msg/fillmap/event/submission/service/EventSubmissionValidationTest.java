package com.msg.fillmap.event.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.submission.dto.EventSubmissionAreaRectDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionCreateRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionLocationRequestDto;
import com.msg.fillmap.event.submission.entity.EventSubmission;
import com.msg.fillmap.event.submission.entity.EventSubmissionLocation;
import com.msg.fillmap.event.submission.entity.EventSubmissionType;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.event.submission.repository.EventSubmissionStatusHistoryRepository;
import com.msg.fillmap.global.exception.ApiException;

/**
 * 신청 검증 3규칙과 대표 격자 계산 (MSG-498 §도메인 로직). DB·S3 가 필요 없는 순수 판정이라 목으로 짠다 —
 * 기간 판정은 "오늘"이 입력이라 고정 Clock 이어야 검증이 성립하고, 그 Clock 을 실 컨텍스트에 꽂을 수 없다.
 * 저장·조회·인가가 걸린 나머지는 통합 테스트(EventSubmissionSubmitTest 등)가 본다.
 */
@DisplayName("행사 등재 신청 검증 (MSG-498)")
class EventSubmissionValidationTest {

	private static final long USER_ID = 42L;

	/** UTC 2026-11-06T16:30Z = KST 2026-11-07T01:30 — 두 시간대의 날짜가 갈리는 순간이다. */
	private static final Clock KST_NEW_DAY = Clock.fixed(Instant.parse("2026-11-06T16:30:00Z"), ZoneOffset.UTC);

	private EventSubmissionRepository submissionRepository;
	private EventSubmissionImageStore imageStore;
	private EventSubmissionServiceImpl service;

	@BeforeEach
	void setUp() {
		submissionRepository = mock(EventSubmissionRepository.class);
		imageStore = mock(EventSubmissionImageStore.class);
		given(submissionRepository.nextSubmissionSequence()).willReturn(7L);
		given(imageStore.confirm(anyLong(), anyString())).willReturn("event-submissions/original/42/a.jpg");
		service = new EventSubmissionServiceImpl(submissionRepository,
			mock(EventSubmissionStatusHistoryRepository.class), imageStore,
			mock(EventSubmissionLocationView.class), KST_NEW_DAY);
	}

	private EventSubmissionCreateRequestDto festival() {
		return request(EventSubmissionType.FESTIVAL, "멀티불꽃쇼, 드론 라이트쇼", null,
			LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7), List.of(location(rect(16859, 16861, 11509, 11515))));
	}

	private EventSubmissionCreateRequestDto request(EventSubmissionType type, String programDescription,
		String operatingHours, LocalDate startsOn, LocalDate endsOn,
		List<EventSubmissionLocationRequestDto> locations) {
		return new EventSubmissionCreateRequestDto(type, "부산불꽃축제", "부산문화관광축제조직위원회",
			startsOn, endsOn, operatingHours, programDescription, "광안리 일원에서 열리는 부산 대표 불꽃 축제",
			"event-submissions/pending/42/a.jpg", locations);
	}

	private EventSubmissionCreateRequestDto withLocations(List<EventSubmissionLocationRequestDto> locations) {
		return request(EventSubmissionType.FESTIVAL, "멀티불꽃쇼, 드론 라이트쇼", null,
			LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7), locations);
	}

	private EventSubmissionAreaRectDto rect(int minGridY, int maxGridY, int minGridX, int maxGridX) {
		return new EventSubmissionAreaRectDto(minGridY, maxGridY, minGridX, maxGridX);
	}

	private EventSubmissionLocationRequestDto location(EventSubmissionAreaRectDto... rects) {
		return new EventSubmissionLocationRequestDto(List.of(rects));
	}

	private EventSubmission submitted(EventSubmissionCreateRequestDto request) {
		service.submit(USER_ID, request);
		ArgumentCaptor<EventSubmission> captor = ArgumentCaptor.forClass(EventSubmission.class);
		then(submissionRepository).should().save(captor.capture());
		return captor.getValue();
	}

	@Nested
	@DisplayName("영역 검증과 81칸 상한")
	class Area {

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("위치 사각형 합산이 81칸이면 통과하고 82칸이면 거부한다")
		void 위치_사각형_합산이_81칸이면_통과하고_82칸이면_거부한다() {
			assertThatCode(() -> service.submit(USER_ID,
				withLocations(List.of(location(rect(100, 108, 200, 208))))))
				.doesNotThrowAnyException();

			assertThatThrownBy(() -> service.submit(USER_ID, withLocations(List.of(
				location(rect(100, 108, 200, 208), rect(200, 200, 300, 300))))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_AREA_LIMIT_EXCEEDED);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("겹치는 사각형은 한 번만 센다 — 합산이면 162칸이라 거부될 입력이다")
		void 겹치는_사각형은_한_번만_센다() {
			assertThatCode(() -> service.submit(USER_ID, withLocations(List.of(
				location(rect(100, 108, 200, 208), rect(100, 108, 200, 208))))))
				.doesNotThrowAnyException();
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("사각형 하나가 81칸을 넘으면 전개 없이 거부한다")
		void 사각형_하나가_81칸을_넘으면_전개_없이_거부한다() {
			// 전개하면 4조 칸이라 선검사가 없으면 메모리를 태운다.
			assertThatThrownBy(() -> service.submit(USER_ID, withLocations(List.of(
				location(rect(1, 99_999, 1, 99_999))))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_AREA_LIMIT_EXCEEDED);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("사각형의 min 이 max 보다 크면 거부한다")
		void 사각형의_min이_max보다_크면_거부한다() {
			assertThatThrownBy(() -> service.submit(USER_ID, withLocations(List.of(
				location(rect(108, 100, 200, 208))))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_SUBMISSION_AREA);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("위치가 없거나 위치에 사각형이 없으면 거부한다")
		void 위치가_없거나_사각형이_없으면_거부한다() {
			for (List<EventSubmissionLocationRequestDto> locations : List.of(
				List.<EventSubmissionLocationRequestDto>of(),
				List.of(new EventSubmissionLocationRequestDto(List.of())))) {
				assertThatThrownBy(() -> service.submit(USER_ID, withLocations(locations)))
					.isInstanceOf(ApiException.class)
					.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_SUBMISSION_AREA);
			}
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("격자 인덱스가 허용 범위 밖이면 거부한다 — 0 이하와 100000 이상")
		void 격자_인덱스가_허용_범위_밖이면_거부한다() {
			for (EventSubmissionAreaRectDto invalid : List.of(
				rect(0, 1, 200, 208), rect(100, 108, 0, 208),
				rect(100, 100_000, 200, 208), rect(100, 108, 200, 100_000))) {
				assertThatThrownBy(() -> service.submit(USER_ID, withLocations(List.of(location(invalid)))))
					.isInstanceOf(ApiException.class)
					.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_SUBMISSION_AREA);
			}
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("위치가 20개면 통과하고 21개면 거부한다")
		void 위치가_20개면_통과하고_21개면_거부한다() {
			assertThatCode(() -> service.submit(USER_ID, withLocations(manyLocations(20))))
				.doesNotThrowAnyException();

			assertThatThrownBy(() -> service.submit(USER_ID, withLocations(manyLocations(21))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_SUBMISSION_AREA);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("위치당 사각형이 81개를 넘으면 거부한다 — 정상 드로잉으로는 나올 수 없는 형태다")
		void 위치당_사각형이_81개를_넘으면_거부한다() {
			List<EventSubmissionAreaRectDto> rects = IntStream.range(0, 82)
				.mapToObj(index -> rect(100 + index, 100 + index, 200, 200))
				.toList();
			assertThatThrownBy(() -> service.submit(USER_ID,
				withLocations(List.of(new EventSubmissionLocationRequestDto(rects)))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_SUBMISSION_AREA);
		}

		private List<EventSubmissionLocationRequestDto> manyLocations(int count) {
			List<EventSubmissionLocationRequestDto> locations = new ArrayList<>();
			for (int index = 0; index < count; index++) {
				locations.add(location(rect(100 + index, 100 + index, 200, 200)));
			}
			return locations;
		}
	}

	@Nested
	@DisplayName("대표 격자 서버 계산")
	class Representative {

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("홀수 직사각형 위치는 정중앙이 대표 격자가 된다")
		void 홀수_직사각형_위치는_정중앙이_대표_격자가_된다() {
			EventSubmission submission = submitted(withLocations(List.of(location(rect(16859, 16861, 11509, 11515)))));

			assertThat(submission.getLocations()).singleElement()
				.extracting(EventSubmissionLocation::getRepresentativeGridId)
				.isEqualTo("16860_11512");
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("직사각형이 아닌 위치는 중심 최근접이 대표 격자가 된다")
		void 직사각형이_아닌_위치는_중심_최근접이_대표_격자가_된다() {
			// 2x2 사각형에 한 칸을 덧댄 L 자 — 경계 상자를 꽉 채우지 않아 정중앙 경로가 성립하지 않는다.
			EventSubmission submission = submitted(withLocations(List.of(
				location(rect(100, 101, 200, 201), rect(102, 102, 200, 200)))));

			assertThat(submission.getLocations()).singleElement()
				.extracting(EventSubmissionLocation::getRepresentativeGridId)
				.isEqualTo("101_200");
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("위치 순번은 요청 배열 순서대로 1부터 매겨진다")
		void 위치_순번은_요청_배열_순서대로_1부터_매겨진다() {
			EventSubmission submission = submitted(withLocations(List.of(
				location(rect(100, 100, 200, 200)), location(rect(300, 300, 400, 400)))));

			assertThat(submission.getLocations())
				.extracting(EventSubmissionLocation::getDisplayOrder,
					EventSubmissionLocation::getRepresentativeGridId)
				.containsExactly(tuple(1, "100_200"), tuple(2, "300_400"));
		}
	}

	@Nested
	@DisplayName("기간과 유형별 항목")
	class Period {

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("종료일이 시작일보다 빠르면 거부한다")
		void 종료일이_시작일보다_빠르면_거부한다() {
			assertThatThrownBy(() -> service.submit(USER_ID, request(EventSubmissionType.FESTIVAL,
				"멀티불꽃쇼", null, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 7),
				List.of(location(rect(100, 100, 200, 200))))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_SUBMISSION_PERIOD);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("종료일이 KST 오늘이면 통과하고 그 전날이면 거부한다 — UTC 로 판정하면 통과해 버릴 시각이다")
		void 종료일이_KST_기준_오늘_이전이면_거부한다() {
			LocalDate kstToday = LocalDate.of(2026, 11, 7);

			assertThatCode(() -> service.submit(USER_ID, request(EventSubmissionType.FESTIVAL, "멀티불꽃쇼", null,
				kstToday.minusDays(10), kstToday, List.of(location(rect(100, 100, 200, 200))))))
				.doesNotThrowAnyException();

			assertThatThrownBy(() -> service.submit(USER_ID, request(EventSubmissionType.FESTIVAL, "멀티불꽃쇼", null,
				kstToday.minusDays(10), kstToday.minusDays(1), List.of(location(rect(100, 100, 200, 200))))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_SUBMISSION_PERIOD);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("축제 신청에 주요 프로그램이 없으면 거부한다")
		void 축제_신청에_주요_프로그램이_없으면_거부한다() {
			assertThatThrownBy(() -> service.submit(USER_ID, request(EventSubmissionType.FESTIVAL, null, null,
				LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7), List.of(location(rect(100, 100, 200, 200))))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_REQUIRED_FIELD_MISSING);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("축제 신청에 운영 시간이 실려 오면 거부한다 — 폼에 없는 값을 조용히 저장하지 않는다")
		void 축제_신청에_운영_시간이_실려_오면_거부한다() {
			assertThatThrownBy(() -> service.submit(USER_ID, request(EventSubmissionType.FESTIVAL, "멀티불꽃쇼",
				"11:00 ~ 20:00", LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7),
				List.of(location(rect(100, 100, 200, 200))))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_REQUIRED_FIELD_MISSING);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("팝업 신청에 운영 시간이 없으면 거부하고, 주요 프로그램이 실려 와도 거부한다")
		void 팝업_신청에_운영_시간이_없으면_거부한다() {
			assertThatThrownBy(() -> service.submit(USER_ID, request(EventSubmissionType.POPUP, null, null,
				LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 20), List.of(location(rect(100, 100, 200, 200))))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_REQUIRED_FIELD_MISSING);

			assertThatThrownBy(() -> service.submit(USER_ID, request(EventSubmissionType.POPUP, "멀티불꽃쇼",
				"11:00 ~ 20:00", LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 20),
				List.of(location(rect(100, 100, 200, 200))))))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_REQUIRED_FIELD_MISSING);
		}
	}

	@Nested
	@DisplayName("신청 번호")
	class SubmissionNo {

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("신청 번호는 FM-{KST 연도}-{4자리 순번} 꼴이다")
		void 신청하면_FM꼴_신청_번호가_부여된다() {
			EventSubmission submission = submitted(festival());

			assertThat(submission.getSubmissionNo()).isEqualTo("FM-2026-0007");
			assertThat(submission.getStatus().name()).isEqualTo("IN_REVIEW");
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("순번이 9999 를 넘으면 자릿수가 자연히 늘어난다 — 리셋 기계가 없어도 겹치지 않는다")
		void 순번이_9999를_넘으면_자릿수가_늘어난다() {
			given(submissionRepository.nextSubmissionSequence()).willReturn(10_000L);

			assertThat(submitted(festival()).getSubmissionNo()).isEqualTo("FM-2026-10000");
		}
	}

	@Nested
	@DisplayName("대표 이미지")
	class Image {

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("제출은 pending 키를 확정본으로 바꿔 저장한다 — 저장되는 것은 요청 키가 아니다")
		void pending_이미지_키가_확정_프리픽스로_복사되어_저장된다() {
			given(imageStore.confirm(USER_ID, "event-submissions/pending/42/a.jpg"))
				.willReturn("event-submissions/original/42/b4d1.jpg");

			assertThat(submitted(festival()).getImageKey()).isEqualTo("event-submissions/original/42/b4d1.jpg");
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("이미지 확정이 실패하면 신청이 저장되지 않는다")
		void 이미지_확정이_실패하면_신청이_저장되지_않는다() {
			given(imageStore.confirm(anyLong(), anyString()))
				.willThrow(new ApiException(EventErrorCode.SUBMISSION_IMAGE_NOT_UPLOADED));

			assertThatThrownBy(() -> service.submit(USER_ID, festival()))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_IMAGE_NOT_UPLOADED);
			then(submissionRepository).should(never()).save(any(EventSubmission.class));
		}
	}
}

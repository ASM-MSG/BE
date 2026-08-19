package com.msg.fillmap.notification.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.notification.dto.NotificationPageResponseDto;
import com.msg.fillmap.notification.dto.NotificationPageResponseDto.NotificationItemResponseDto;
import com.msg.fillmap.notification.dto.NotificationUnreadCountResponseDto;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.exception.NotificationErrorCode;
import com.msg.fillmap.notification.service.NotificationInboxService;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 알림함 API 경계 검증 (MSG-434). 서비스는 목이고 여기서 보는 것은 셋뿐이다 — 스프링이 조립한
 * 매퍼로 직렬화했을 때 발송 내부 상태가 새지 않는지(비기능 보안), 토큰 없이 거부되는지,
 * 성공 응답이 공통 래핑 형식인지. 도메인 동작은 NotificationInboxIntegrationTest 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("NotificationInboxController 알림함 API — 응답 형식·인증 경계 (MSG-434)")
class NotificationInboxControllerTest {

	private static final long USER_ID = 42L;
	private static final String URL = "/api/notifications";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private NotificationInboxService notificationInboxService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	private NotificationPageResponseDto onePage() {
		return new NotificationPageResponseDto(List.of(new NotificationItemResponseDto(
			123L, NotificationCategory.BADGE, "새 뱃지 획득", "'첫 걸음' 뱃지를 획득했어요",
			LocalDateTime.of(2026, 8, 19, 2, 11), false)), 123L, true);
	}

	@Nested
	@DisplayName("응답 형식")
	class ResponseShape {

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("응답에 status·event_key·retry_count·last_error 가 실리지 않는다 — 비기능 보안")
		void 응답에_status_event_key_retry_count_last_error가_실리지_않는다() throws Exception {
			given(notificationInboxService.getInbox(USER_ID, null, 20)).willReturn(onePage());

			mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.notifications[0].notificationId").value(123))
				.andExpect(jsonPath("$.data.notifications[0].category").value("BADGE"))
				.andExpect(jsonPath("$.data.notifications[0].title").value("새 뱃지 획득"))
				.andExpect(jsonPath("$.data.notifications[0].read").value(false))
				// 전역 코덱이 UTC Z 표기로 내린다 — 손조립 매퍼면 이 형식이 안 나온다.
				.andExpect(jsonPath("$.data.notifications[0].createdAt").value("2026-08-19T02:11:00Z"))
				.andExpect(jsonPath("$.data.notifications[0].status").doesNotExist())
				.andExpect(jsonPath("$.data.notifications[0].eventKey").doesNotExist())
				.andExpect(jsonPath("$.data.notifications[0].retryCount").doesNotExist())
				.andExpect(jsonPath("$.data.notifications[0].lastError").doesNotExist())
				.andExpect(jsonPath("$.data.notifications[0].readAt").doesNotExist())
				.andExpect(jsonPath("$.data.nextCursor").value(123))
				.andExpect(jsonPath("$.data.hasNext").value(true));

			// 미지정 size 는 컨트롤러 기본값 20 으로 서비스에 전달된다 (D-3 클램프 계약의 진입점).
			then(notificationInboxService).should().getInbox(USER_ID, null, 20);
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("커서와 size 는 그대로 서비스에 전달된다 — Long 커서 바인딩")
		void 커서와_size는_그대로_서비스에_전달된다() throws Exception {
			given(notificationInboxService.getInbox(USER_ID, 123L, 5)).willReturn(onePage());

			mockMvc.perform(get(URL + "?cursor=123&size=5").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk());

			then(notificationInboxService).should().getInbox(USER_ID, 123L, 5);
		}

		@Test
		@DisplayName("커서가 숫자가 아니면 공통 400 이다 — 별도 INVALID_CURSOR 코드가 없는 근거 (D-3)")
		void 커서가_숫자가_아니면_공통_400이다() throws Exception {
			mockMvc.perform(get(URL + "?cursor=abc").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isBadRequest());

			then(notificationInboxService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("인증 경계와 공통 래핑")
	class AuthAndEnvelope {

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("엔드포인트 4종은 토큰 없이 거부되고 성공 응답은 공통 래핑 형식이다 — 비기능 보안")
		void 엔드포인트_4종은_토큰_없이_거부되고_성공_응답은_공통_래핑_형식이다() throws Exception {
			mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
			mockMvc.perform(get(URL + "/unread-count")).andExpect(status().isUnauthorized());
			mockMvc.perform(patch(URL + "/1/read")).andExpect(status().isUnauthorized());
			mockMvc.perform(patch(URL + "/read-all")).andExpect(status().isUnauthorized());
			then(notificationInboxService).shouldHaveNoInteractions();

			given(notificationInboxService.getInbox(USER_ID, null, 20)).willReturn(onePage());
			given(notificationInboxService.getUnreadCount(USER_ID))
				.willReturn(new NotificationUnreadCountResponseDto(3));

			mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.message").value("성공"));
			mockMvc.perform(get(URL + "/unread-count").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data.count").value(3));
			// 읽음 2종은 data 가 null 이고 래핑만 온다. doesNotExist 는 "키 없음"과 "값 null"을 구분하지 못하는데,
			// ApiResponseDto.data 는 required 키라 성공 응답에도 키가 있어야 한다 — 존재와 null 을 따로 단언한다.
			mockMvc.perform(patch(URL + "/1/read").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data").hasJsonPath())
				.andExpect(jsonPath("$.data").value(nullValue()));
			mockMvc.perform(patch(URL + "/read-all").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data").hasJsonPath())
				.andExpect(jsonPath("$.data").value(nullValue()));

			then(notificationInboxService).should().markRead(USER_ID, 1L);
			then(notificationInboxService).should().markAllRead(USER_ID);
		}

		// 검증: FR-NOTI-17
		@Test
		@DisplayName("없거나 타인 소유인 알림의 읽음 처리는 10404 다 — 서비스 예외의 핸들러 변환")
		void 없거나_타인_소유인_알림의_읽음_처리는_10404다() throws Exception {
			willThrow(new ApiException(NotificationErrorCode.NOTIFICATION_NOT_FOUND))
				.given(notificationInboxService).markRead(USER_ID, 999L);

			mockMvc.perform(patch(URL + "/999/read").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.developCode").value(10404))
				// 실패 응답도 같은 봉투다 — data 키는 있고 값만 null.
				.andExpect(jsonPath("$.data").hasJsonPath())
				.andExpect(jsonPath("$.data").value(nullValue()));
		}
	}
}

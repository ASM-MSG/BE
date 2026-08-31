package com.msg.fillmap.event.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.msg.fillmap.response.ErrorCodeIfs;

/**
 * 행사 도메인 에러 코드 — developCode 13xxx 대역 (MSG-438 배정, response-pattern.md 대역 표가 정본).
 * EVENT_NOT_FOUND 는 "없는 회차"와 "아직 노출 전인 회차"가 함께 쓴다 — 노출 전 회차에 다른 코드를 주면
 * 순차 id 대입만으로 미공개 행사의 존재가 드러나기 때문이다 (MSG-439 §API 명세 존재 은닉).
 * 뷰포트 두 코드는 mission·grid 와 같은 판정·같은 상한을 쓰고 대역만 다르다 — 도메인 예외는 도메인이 갖는다.
 * 생명주기 세 코드(MSG-442)가 409 인 이유는 권한이 아니라 리소스(행사)의 현재 상태와 요청이 충돌하는
 * 거절이기 때문이다 (DUPLICATE_REPORT 11409 선례). 시작 전과 마감을 가르는 것은 FE 가 버튼 비활성화
 * 근거를 구분해야 해서다.
 */
@Getter
@AllArgsConstructor
public enum EventErrorCode implements ErrorCodeIfs {

	INVALID_VIEWPORT(13400, HttpStatus.BAD_REQUEST, "유효하지 않은 지도 범위입니다"),
	VIEWPORT_TOO_LARGE(13401, HttpStatus.BAD_REQUEST, "조회 범위가 너무 넓습니다"),
	INVALID_CURSOR(13402, HttpStatus.BAD_REQUEST, "유효하지 않은 커서입니다"),
	// 댓글 권한 실패는 404 로 뭉개지 않는다 — 남의 댓글은 목록에서 이미 공개된 존재라 숨길 것이 없다
	// (영상 존재 은닉과 성격이 다르고 VIDEO_FORBIDDEN 3403 과 같은 결이다, MSG-441).
	EVENT_COMMENT_FORBIDDEN(13403, HttpStatus.FORBIDDEN, "본인의 댓글만 수정하거나 삭제할 수 있습니다"),
	EVENT_NOT_FOUND(13404, HttpStatus.NOT_FOUND, "행사를 찾을 수 없습니다"),
	EVENT_LOCATION_NOT_FOUND(13405, HttpStatus.NOT_FOUND, "행사 위치를 찾을 수 없습니다"),
	EVENT_VIDEO_NOT_FOUND(13406, HttpStatus.NOT_FOUND, "행사 영상을 찾을 수 없습니다"),
	EVENT_COMMENT_NOT_FOUND(13407, HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다"),
	EVENT_UPLOAD_CLOSED(13409, HttpStatus.CONFLICT, "행사 영상 업로드가 마감되었습니다"),
	// 마감(13409)과 같은 창 위반이지만 FE 안내 문구가 달라야 해 코드를 가른다 (MSG-440, 2026-08-21 확정).
	// MSG-442 초안의 13420·13421 은 440·442 레인 조정으로 이 두 코드에 통일됐다 (2026-08-21 합의).
	EVENT_UPLOAD_NOT_STARTED(13410, HttpStatus.CONFLICT, "행사 시작 전에는 영상을 올릴 수 없습니다"),
	// 마감(13409)과 문구를 맞춘다 (2026-08-21 사용자 확정). 잠금 시점 번복으로 반응 창이 업로드 창과
	// 같은 순간(종료 + 30일)에 닫히게 돼 두 코드가 같은 경계를 지키는 쌍이 됐는데, 한쪽만 "종료"라고
	// 말하면 사용자가 같은 사건을 다른 사건으로 읽는다. 옛 문구는 실제 차단 사유(보관 전환)가 아니라
	// 엉뚱한 경계(종료)를 지목해서, 종료 후 30일 동안 댓글을 달아 온 사용자가 31일째에 "종료돼서
	// 안 된다"는 답을 듣는 모순도 있었다. developCode·HttpStatus 는 불변이고 표시 문자열만 바꿨다.
	EVENT_INTERACTION_LOCKED(13422, HttpStatus.CONFLICT, "행사 영상 댓글·도움돼요가 마감되었습니다"),

	// 행사 등재 신청 블록 (MSG-498). 13423~13429 를 비워 두는 것은 폐기 이력이 있는 13420·13421 부근을
	// 피해 번호만 봐도 신청 블록임이 읽히게 하기 위해서다.
	// 없는 신청과 남의 신청이 같은 코드를 쓰는 것은 존재 은닉이다 (FR-14) — 조회를 항상 id + userId 쌍으로
	// 하므로 두 경우의 코드 경로 자체가 하나이고, 응답이 갈릴 여지가 없다.
	SUBMISSION_NOT_FOUND(13430, HttpStatus.NOT_FOUND, "신청을 찾을 수 없습니다"),
	INVALID_SUBMISSION_AREA(13431, HttpStatus.BAD_REQUEST, "유효하지 않은 위치 영역입니다"),
	SUBMISSION_AREA_LIMIT_EXCEEDED(13432, HttpStatus.BAD_REQUEST, "위치 하나의 영역은 최대 81칸입니다"),
	INVALID_SUBMISSION_PERIOD(13433, HttpStatus.BAD_REQUEST, "행사 기간이 유효하지 않습니다"),
	// 권한이 아니라 신청의 현재 상태와 요청이 충돌하는 거절이라 409 다 (13409 · 11409 선례).
	SUBMISSION_NOT_EDITABLE(13434, HttpStatus.CONFLICT, "반려된 신청만 수정할 수 있습니다"),
	SUBMISSION_IMAGE_KEY_INVALID(13435, HttpStatus.BAD_REQUEST, "유효하지 않은 이미지 키입니다"),
	SUBMISSION_IMAGE_NOT_UPLOADED(13436, HttpStatus.BAD_REQUEST, "업로드되지 않은 이미지입니다"),
	SUBMISSION_IMAGE_UNSUPPORTED(13437, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "jpg, png 이미지만 올릴 수 있습니다"),
	SUBMISSION_IMAGE_TOO_LARGE(13438, HttpStatus.PAYLOAD_TOO_LARGE, "이미지는 최대 10MB 입니다"),
	SUBMISSION_REQUIRED_FIELD_MISSING(13439, HttpStatus.BAD_REQUEST, "등록 유형에 필요한 항목이 올바르지 않습니다"),

	// 이벤트 참여형의 부모 회차 검증 (MSG-502). 존재를 은닉하지 않는 것은 승인 이벤트 목록이 행사 운영자
	// 전원에게 같은 전량을 보여줘 회차의 존재가 비밀이 아니기 때문이다 — 은닉 대상은 남의 신청(13430)뿐이다.
	PARENT_EVENT_NOT_FOUND(13440, HttpStatus.NOT_FOUND, "참여할 이벤트를 찾을 수 없습니다"),
	// 종료 판정(endsAt <= now)은 목록 노출 조건의 여집합이라 정각에도 둘이 갈리지 않는다. 상태 충돌 409 (13434 선례).
	PARENT_EVENT_CLOSED(13441, HttpStatus.CONFLICT, "종료된 이벤트에는 참여를 신청할 수 없습니다"),

	// 관리자 심사 블록 (MSG-500). 13440 대는 MSG-502(참여형) 예약이라 13450 부터 쓴다.
	// 목록 파라미터 두 코드는 판정과 메시지가 MSG-499 관리자 큐(1424·1425)와 같고 대역만 event 다 —
	// 도메인 예외는 도메인이 갖는다는 규칙(뷰포트 두 코드가 mission·grid 와 같은 판정에 다른 대역인 것과 같은 결).
	SUBMISSION_STATUS_NOT_REVIEWABLE(13450, HttpStatus.CONFLICT, "심사 중인 신청만 승인하거나 반려할 수 있습니다"),
	SUBMISSION_PERIOD_PASSED(13451, HttpStatus.CONFLICT, "행사 기간이 이미 지나 승인할 수 없습니다"),
	SUBMISSION_GRID_CONFLICT(13452, HttpStatus.CONFLICT, "신청 영역이 부모 이벤트의 다른 위치와 겹칩니다"),
	EVENT_ALREADY_UNPUBLISHED(13453, HttpStatus.CONFLICT, "이미 노출이 중지된 행사입니다"),
	INVALID_REJECT_REASON(13454, HttpStatus.BAD_REQUEST, "반려 항목 코드가 올바르지 않습니다"),
	INVALID_SUBMISSION_STATUS_FILTER(13455, HttpStatus.BAD_REQUEST, "지원하지 않는 상태 필터입니다"),
	INVALID_PAGE_RANGE(13456, HttpStatus.BAD_REQUEST, "페이지 번호 또는 크기가 유효하지 않습니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}

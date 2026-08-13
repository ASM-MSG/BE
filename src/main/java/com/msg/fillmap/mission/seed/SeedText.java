package com.msg.fillmap.mission.seed;

import tools.jackson.databind.JsonNode;

/**
 * 시더 3종이 공유하는 화면용 텍스트 추출·정규화 (MSG-383 D3·D5). 세 원본이 같은 컬럼
 * (missions.place_name · description)에 값을 넣으므로 결측 규칙과 절단 길이와 {@code <br>} 규칙을 한 곳에
 * 둔다 — 축제 static 헬퍼를 팝업 시더가 그대로 호출하는 선례(MSG-235 D2 "복제 금지")와 같은 판단이다.
 */
final class SeedText {

	/** missions.place_name VARCHAR(200) 방어 절단 — title 과 같은 방어선(§D2). */
	private static final int PLACE_NAME_MAX_LENGTH = 200;

	/** {@code <br>}·{@code <br/>}·{@code <br />} 대소문자 무관 (§D5). 다른 태그는 전수 확인 결과 없다. */
	private static final String BR_TAG = "(?i)<br\\s*/?>";

	private SeedText() {
	}

	/**
	 * 화면용 텍스트 추출 — 누락·null·공백은 전부 null 이다. 빈 문자열을 그대로 저장하면 "값 없음"을 FE 가
	 * 두 가지(null·"")로 분기해야 한다(축제 homepage 결측 34%가 이 경로다).
	 *
	 * 결측이 거부가 아닌 이유는 판정에 쓰이는 값(좌표·날짜·id·스팟)과 무게가 다르기 때문이다(D4) — 화면 값
	 * 하나가 비었다고 산출물 전체를 되돌리면 운영자가 손댈 수 없다. 코스의 판정용 {@code requireText} 와
	 * 계약이 다른 것도 그래서다.
	 */
	static String text(JsonNode owner, String field) {
		JsonNode value = owner.path(field);
		if (!value.isTextual() || value.asString().isBlank()) {
			return null;
		}
		return value.asString();
	}

	static String truncatePlaceName(String placeName) {
		if (placeName == null || placeName.length() <= PLACE_NAME_MAX_LENGTH) {
			return placeName;
		}
		return placeName.substring(0, PLACE_NAME_MAX_LENGTH);
	}

	/**
	 * 소개문의 {@code <br>} 태그를 개행 하나로 바꾼다 (§D5). 다른 태그 제거·HTML 이스케이프는 하지
	 * 않는다 — 지금 없는 입력을 상대로 한 새니타이저이고, 값을 문자열로 렌더하는 클라이언트가 이미
	 * 이스케이프한다. 원문 그대로 노출이 PRD 확정 사항이라 요약·재작성도 없다.
	 */
	static String normalizeBreaks(String description) {
		return description == null ? null : description.replaceAll(BR_TAG, "\n");
	}
}

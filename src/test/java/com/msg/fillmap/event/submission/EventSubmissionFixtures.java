package com.msg.fillmap.event.submission;

import java.util.UUID;

/**
 * 행사 등재 신청 요청 본문 조립기 (MSG-498 테스트 공용). 본문을 문자열로 만드는 이유는 "필드를 아예 빼면
 * 어떻게 되나"(재제출의 이미지 키 생략)를 검증해야 하는데 DTO 로 만들면 그 상태를 표현할 수 없기 때문이다.
 */
public final class EventSubmissionFixtures {

	/** 부산 광안리 일대 — 3행 7열(21칸) 홀수 직사각형이라 대표 격자가 정중앙으로 결정된다. */
	public static final String GWANGALLI_RECT = rect(16859, 16861, 11509, 11515);
	public static final String GWANGALLI_CENTER = "16860_11512";

	private EventSubmissionFixtures() {
	}

	public static String rect(int minGridY, int maxGridY, int minGridX, int maxGridX) {
		return """
			{"minGridY": %d, "maxGridY": %d, "minGridX": %d, "maxGridX": %d}"""
			.formatted(minGridY, maxGridY, minGridX, maxGridX);
	}

	/** 사각형들을 위치 하나로 묶는다 — 위치에는 이름 필드가 없다 (피그마 #102). */
	public static String location(String... rects) {
		return """
			{"areaRects": [%s]}""".formatted(String.join(", ", rects));
	}

	public static String pendingKey(long userId) {
		return "event-submissions/pending/%d/%s.jpg".formatted(userId, UUID.randomUUID());
	}

	public static String festivalBody(long userId, String... locations) {
		return festivalBody(pendingKey(userId), locations);
	}

	public static String festivalBody(String imageS3Key, String... locations) {
		return body("부산불꽃축제", "2026-11-07", "2026-11-07",
			"광안리해수욕장 일원에서 열리는 부산 대표 불꽃 축제", imageS3Key, locations);
	}

	/** 이미 끝난 행사 — 종료일이 KST 오늘 이전이라 거부 대상이다 (D-6). */
	public static String pastFestivalBody(long userId, String... locations) {
		return body("2020 부산불꽃축제", "2020-11-06", "2020-11-07",
			"광안리해수욕장 일원에서 열렸던 부산 대표 불꽃 축제", pendingKey(userId), locations);
	}

	/** 팝업 신청 — 유형별 필수 항목이 축제와 반대다(운영 시간 있음·주요 프로그램 없음). */
	public static String popupBody(long userId, String... locations) {
		return """
			{
				"type": "POPUP",
				"title": "필맵 팝업스토어",
				"organizerName": "필맵 주식회사",
				"startsOn": "2026-11-07",
				"endsOn": "2026-11-07",
				"operatingHours": "11:00 ~ 20:00",
				"description": "광안리 해변가에서 여는 필맵 브랜드 팝업스토어",
				"imageS3Key": "%s",
				"locations": [%s]
			}""".formatted(pendingKey(userId), String.join(", ", locations));
	}

	public static String festivalBodyWithTitle(long userId, String title, String... locations) {
		return body(title, "2026-11-07", "2026-11-07",
			"광안리해수욕장 일원에서 열리는 부산 대표 불꽃 축제", pendingKey(userId), locations);
	}

	public static String festivalBodyWithDescription(long userId, String description, String... locations) {
		return body("부산불꽃축제", "2026-11-07", "2026-11-07", description, pendingKey(userId), locations);
	}

	/** 재제출 본문 — 제출에서 type 을 뺀 전체다. imageS3Key 가 null 이면 필드 자체가 빠져 기존 이미지 유지 계약을 탄다. */
	public static String updateBody(String title, String imageS3Key, String... locations) {
		return """
			{
				"title": "%s",
				"organizerName": "부산문화관광축제조직위원회",
				"startsOn": "2026-11-07",
				"endsOn": "2026-11-07",
				"programDescription": "멀티불꽃쇼, 뮤직 불꽃쇼, 드론 라이트쇼 운영",
				"description": "광안리해수욕장 일원에서 열리는 부산 대표 불꽃 축제",
				%s"locations": [%s]
			}""".formatted(title, field("imageS3Key", imageS3Key), String.join(", ", locations));
	}

	private static String body(String title, String startsOn, String endsOn, String description,
		String imageS3Key, String... locations) {
		return """
			{
				"type": "FESTIVAL",
				"title": "%s",
				"organizerName": "부산문화관광축제조직위원회",
				"startsOn": "%s",
				"endsOn": "%s",
				"programDescription": "멀티불꽃쇼, 뮤직 불꽃쇼, 드론 라이트쇼 운영",
				"description": "%s",
				"imageS3Key": "%s",
				"locations": [%s]
			}""".formatted(title, startsOn, endsOn, description, imageS3Key, String.join(", ", locations));
	}

	private static String field(String name, String value) {
		return value == null ? "" : "\"%s\": \"%s\",\n\t\t\t".formatted(name, value);
	}
}

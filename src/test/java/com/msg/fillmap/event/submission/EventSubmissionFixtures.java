package com.msg.fillmap.event.submission;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 행사 등재 신청 요청 본문 조립기 (MSG-498 테스트 공용). 본문을 문자열로 만드는 이유는 "필드를 아예 빼면
 * 어떻게 되나"(재제출의 이미지 키 생략)를 검증해야 하는데 DTO 로 만들면 그 상태를 표현할 수 없기 때문이다.
 */
public final class EventSubmissionFixtures {

	/** 부산 광안리 일대 — 3행 7열(21칸) 홀수 직사각형이라 대표 격자가 정중앙으로 결정된다. */
	public static final String GWANGALLI_RECT = rect(16859, 16861, 11509, 11515);
	public static final String GWANGALLI_CENTER = "16860_11512";

	/** 참여 방식 (MSG-502) — EVENT 전용 필수 서술 항목이다. */
	public static final String PARTICIPATION_METHOD = "부스 방문 후 현장에서 인증 영상을 촬영해 업로드하면 참여가 완료됩니다";

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

	/**
	 * 실행일 기준 상대 날짜 (EventSubmissionCommitBoundaryTest 선례). 기간을 고정 날짜로 박으면 그날이 지나는
	 * 순간 모든 성공 경로가 기간 검증(13433)에서 먼저 깨진다 — 달력이 트리거인 시한폭탄이라 상대값으로 만든다.
	 * KST 오늘 이상이면 통과이고 UTC 오늘은 KST 오늘보다 앞서지 않으므로, 양수 offset 은 두 시간대 어디서도 안전하다.
	 */
	private static String daysFromToday(int days) {
		return LocalDate.now(ZoneOffset.UTC).plusDays(days).toString();
	}

	public static String pendingKey(long userId) {
		return "event-submissions/pending/%d/%s.jpg".formatted(userId, UUID.randomUUID());
	}

	public static String festivalBody(long userId, String... locations) {
		return festivalBody(pendingKey(userId), locations);
	}

	public static String festivalBody(String imageS3Key, String... locations) {
		return body("부산불꽃축제", daysFromToday(30), daysFromToday(30),
			"광안리해수욕장 일원에서 열리는 부산 대표 불꽃 축제", imageS3Key, locations);
	}

	/** 이미 끝난 행사 — 종료일이 KST 오늘 이전이라 거부 대상이다 (D-6). */
	public static String pastFestivalBody(long userId, String... locations) {
		return body("2020 부산불꽃축제", "2020-11-06", "2020-11-07",
			"광안리해수욕장 일원에서 열렸던 부산 대표 불꽃 축제", pendingKey(userId), locations);
	}

	public static String festivalBodyWithTitle(long userId, String title, String... locations) {
		return body(title, daysFromToday(30), daysFromToday(30),
			"광안리해수욕장 일원에서 열리는 부산 대표 불꽃 축제", pendingKey(userId), locations);
	}

	public static String festivalBodyWithDescription(long userId, String description, String... locations) {
		return body("부산불꽃축제", daysFromToday(30), daysFromToday(30), description, pendingKey(userId), locations);
	}

	/** 이벤트 참여형 신청 본문 (MSG-502) — 부모 회차 id 와 참여 방식이 유형별 필수다. */
	public static String eventBody(long userId, long parentOccurrenceId, String... locations) {
		return eventBodyWithMethod(userId, parentOccurrenceId, PARTICIPATION_METHOD, locations);
	}

	public static String eventBodyWithMethod(long userId, long parentOccurrenceId, String participationMethod,
		String... locations) {
		return """
			{
				"type": "EVENT",
				"parentOccurrenceId": %d,
				"title": "필맵 스탬프 투어",
				"organizerName": "필맵 파트너스",
				"startsOn": "%s",
				"endsOn": "%s",
				"participationMethod": "%s",
				"description": "부산국제영화제 기간에 영화의전당 일대에서 진행하는 스탬프 투어입니다",
				"imageS3Key": "%s",
				"locations": [%s]
			}""".formatted(parentOccurrenceId, daysFromToday(30), daysFromToday(39), participationMethod,
			pendingKey(userId), String.join(", ", locations));
	}

	/**
	 * 이벤트 참여형 재제출 본문 (MSG-502). {@code parentOccurrenceId} 를 넘기면 본문에 실리는데, 재제출 DTO 에는
	 * 그 필드가 없어 역직렬화에서 버려진다 — 부모 불변(D-3)을 테스트가 실제 요청으로 찍기 위한 손잡이다.
	 */
	public static String eventUpdateBody(String title, Long parentOccurrenceId, String... locations) {
		return """
			{
				%s"title": "%s",
				"organizerName": "필맵 파트너스",
				"startsOn": "%s",
				"endsOn": "%s",
				"participationMethod": "%s",
				"description": "부산국제영화제 기간에 영화의전당 일대에서 진행하는 스탬프 투어입니다",
				"locations": [%s]
			}""".formatted(parentOccurrenceId == null ? "" : "\"parentOccurrenceId\": %d,\n\t\t\t".formatted(
				parentOccurrenceId), title, daysFromToday(30), daysFromToday(39), PARTICIPATION_METHOD,
			String.join(", ", locations));
	}

	/** 재제출 본문 — 제출에서 type 을 뺀 전체다. imageS3Key 가 null 이면 필드 자체가 빠져 기존 이미지 유지 계약을 탄다. */
	public static String updateBody(String title, String imageS3Key, String... locations) {
		return """
			{
				"title": "%s",
				"organizerName": "부산문화관광축제조직위원회",
				"startsOn": "%s",
				"endsOn": "%s",
				"programDescription": "멀티불꽃쇼, 뮤직 불꽃쇼, 드론 라이트쇼 운영",
				"description": "광안리해수욕장 일원에서 열리는 부산 대표 불꽃 축제",
				%s"locations": [%s]
			}""".formatted(title, daysFromToday(30), daysFromToday(30), field("imageS3Key", imageS3Key),
			String.join(", ", locations));
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

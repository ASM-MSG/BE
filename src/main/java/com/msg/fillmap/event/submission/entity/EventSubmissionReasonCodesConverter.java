package com.msg.fillmap.event.submission.entity;

import java.util.Arrays;
import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 반려 항목 코드 목록 ↔ 쉼표 연결 문자열 ("AREA,INFO") 매핑 (MSG-498). 값이 4종 고정이고 순서에 의미가
 * 없어 JSONB 같은 방언 의존 타입으로 얻을 것이 없다 — JPA 표준 매핑 하나로 끝난다.
 * 비어 있거나 없는 사유는 NULL 이다 (반려 아닌 상태 행의 저장 계약, DDL CHECK 과 같은 규칙).
 */
@Converter
public class EventSubmissionReasonCodesConverter
	implements AttributeConverter<List<EventSubmissionReasonCode>, String> {

	private static final String DELIMITER = ",";

	@Override
	public String convertToDatabaseColumn(List<EventSubmissionReasonCode> codes) {
		if (codes == null || codes.isEmpty()) {
			return null;
		}
		return codes.stream().map(Enum::name).reduce((left, right) -> left + DELIMITER + right).orElseThrow();
	}

	@Override
	public List<EventSubmissionReasonCode> convertToEntityAttribute(String column) {
		if (column == null || column.isBlank()) {
			return null;
		}
		return Arrays.stream(column.split(DELIMITER))
			.map(String::trim)
			.map(EventSubmissionReasonCode::valueOf)
			.toList();
	}
}

package com.msg.fillmap.response;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// data 키는 항상 직렬화된다(에러·Void 성공은 값이 null) — 키 상존이므로 required 에 넣는다
// (MSG-311 Codex 리뷰 반영, PR #102 email 선례의 "필드 상존 = required" 계약). 값 nullable 마킹은
// 미도입 — springdoc 이 제네릭 $ref 필드에 {"type":"null","$ref":…} 모순 스키마를 출력한다(실측).
// 문서화되는 200 응답에서 data 는 실제 비null 이고, Void 성공은 빈 스키마가 null 을 허용한다.
@Schema(requiredProperties = {"developCode", "message", "data"})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponseDto<T> {

	private Integer developCode;
	private String message;
	private T data;
}
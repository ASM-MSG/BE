package com.msg.fillmap.response;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// data 는 에러 응답에서 null 이므로 required 에 넣지 않는다.
@Schema(requiredProperties = {"developCode", "message"})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponseDto<T> {

	private Integer developCode;
	private String message;
	private T data;
}
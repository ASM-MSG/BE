package com.msg.fillmap.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponseDto<T> {

	private Integer developCode;
	private String message;
	private T body;
}
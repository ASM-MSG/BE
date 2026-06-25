package com.msg.fillmap.response;

import org.springframework.http.HttpStatusCode;

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
	private HttpStatusCode httpStatus;
	private String message;
	private T body;
}
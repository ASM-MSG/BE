package com.msg.fillmap.response;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class SuccessResponse<T> extends ResponseEntity<ApiResponseDto<T>> {

	private static final int SUCCESS_CODE = 200;
	private static final String SUCCESS_MESSAGE = "성공";

	public SuccessResponse(T data) {
		super(
			ApiResponseDto.<T>builder()
				.developCode(SUCCESS_CODE)
				.message(SUCCESS_MESSAGE)
				.body(data)
				.build(),
			HttpStatus.OK
		);
	}

	public static <T> SuccessResponse<T> of(T data) {
		return new SuccessResponse<>(data);
	}
}
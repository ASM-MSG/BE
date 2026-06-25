package com.msg.fillmap.response;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class SuccessResponse<T> extends ResponseEntity<ApiResponseDto<T>> {

	public SuccessResponse(T data) {
		super(
			ApiResponseDto.<T>builder()
				.developCode(ErrorCode.OK.getErrorCode())
				.httpStatus(ErrorCode.OK.getHttpStatus())
				.message(ErrorCode.OK.getMessage())
				.body(data)
				.build(),
			HttpStatus.OK
		);
	}

	public static <T> SuccessResponse<T> of(T data) {
		return new SuccessResponse<>(data);
	}
}
package com.msg.fillmap.global;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.response.ApiResponseDto;
import com.msg.fillmap.response.ErrorCode;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiResponseDto<Object>> handleApiException(ApiException e) {
		var errorCode = e.getErrorCode();
		return ResponseEntity
				.status(errorCode.getHttpStatus())
				.body(ApiResponseDto.builder()
						.developCode(errorCode.getErrorCode())
						.httpStatus(errorCode.getHttpStatus())
						.message(errorCode.getMessage())
						.build());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponseDto<Object>> handleException(Exception e) {
		return ResponseEntity
				.status(ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
				.body(ApiResponseDto.builder()
						.developCode(ErrorCode.INTERNAL_SERVER_ERROR.getErrorCode())
						.httpStatus(ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
						.message(ErrorCode.INTERNAL_SERVER_ERROR.getMessage())
						.build());
	}
}
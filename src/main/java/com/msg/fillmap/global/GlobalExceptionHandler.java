package com.msg.fillmap.global;

import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
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
						.message(e.getMessage() != null ? e.getMessage() : errorCode.getMessage())
						.build());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponseDto<Object>> handleValidation(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
				.collect(Collectors.joining(", "));
		return ResponseEntity
				.status(ErrorCode.BAD_REQUEST.getHttpStatus())
				.body(ApiResponseDto.builder()
						.developCode(ErrorCode.BAD_REQUEST.getErrorCode())
						.httpStatus(ErrorCode.BAD_REQUEST.getHttpStatus())
						.message(message.isBlank() ? ErrorCode.BAD_REQUEST.getMessage() : message)
						.build());
	}

	// 필수 @RequestParam 누락은 클라이언트 잘못이라 400 이다. 이 핸들러가 없으면 아래 catch-all(Exception)이
	// 프레임워크 바인딩 예외까지 삼켜 500 을 내므로 명시적으로 400 으로 매핑한다.
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponseDto<Object>> handleMissingParameter(MissingServletRequestParameterException e) {
		return ResponseEntity
				.status(ErrorCode.BAD_REQUEST.getHttpStatus())
				.body(ApiResponseDto.builder()
						.developCode(ErrorCode.BAD_REQUEST.getErrorCode())
						.httpStatus(ErrorCode.BAD_REQUEST.getHttpStatus())
						.message("필수 파라미터 누락: " + e.getParameterName())
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
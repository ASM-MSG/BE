package com.msg.fillmap.global;

import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
						.message("필수 파라미터 누락: " + e.getParameterName())
						.build());
	}

	// @RequestBody 본문 누락·JSON 파싱 불가도 클라이언트 잘못이라 400 이다 — 파라미터 누락(위)의 본문판.
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponseDto<Object>> handleNotReadable(HttpMessageNotReadableException e) {
		return ResponseEntity
				.status(ErrorCode.BAD_REQUEST.getHttpStatus())
				.body(ApiResponseDto.builder()
						.developCode(ErrorCode.BAD_REQUEST.getErrorCode())
						.message("요청 본문이 없거나 읽을 수 없습니다")
						.build());
	}

	// 경로변수/파라미터 타입 불일치(예: /api/videos/abc 의 videoId)도 클라이언트 잘못이라 400 이다 — 누락(위)과 동일 계열.
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponseDto<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return ResponseEntity
				.status(ErrorCode.BAD_REQUEST.getHttpStatus())
				.body(ApiResponseDto.builder()
						.developCode(ErrorCode.BAD_REQUEST.getErrorCode())
						.message("파라미터 타입 불일치: " + e.getName())
						.build());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponseDto<Object>> handleException(Exception e) {
		return ResponseEntity
				.status(ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
				.body(ApiResponseDto.builder()
						.developCode(ErrorCode.INTERNAL_SERVER_ERROR.getErrorCode())
						.message(ErrorCode.INTERNAL_SERVER_ERROR.getMessage())
						.build());
	}
}
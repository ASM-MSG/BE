package com.msg.fillmap.global.exception;

import com.msg.fillmap.response.ErrorCodeIfs;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {

	private final ErrorCodeIfs errorCode;

	public ApiException(ErrorCodeIfs errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	public ApiException(ErrorCodeIfs errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public ApiException(ErrorCodeIfs errorCode, Throwable cause) {
		super(errorCode.getMessage(), cause);
		this.errorCode = errorCode;
	}
}
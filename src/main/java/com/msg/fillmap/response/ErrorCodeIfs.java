package com.msg.fillmap.response;

import org.springframework.http.HttpStatusCode;

public interface ErrorCodeIfs {

	HttpStatusCode getHttpStatus();

	Integer getErrorCode();

	String getMessage();
}
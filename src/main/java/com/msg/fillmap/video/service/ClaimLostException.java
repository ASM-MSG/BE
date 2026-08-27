package com.msg.fillmap.video.service;

public class ClaimLostException extends IllegalStateException {

	public ClaimLostException(Long jobId) {
		super("인코딩 작업 claim이 만료되거나 다른 노드로 넘어감: jobId=" + jobId);
	}
}

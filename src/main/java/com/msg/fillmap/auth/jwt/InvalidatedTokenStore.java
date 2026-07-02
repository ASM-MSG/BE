package com.msg.fillmap.auth.jwt;

import java.time.Instant;

public interface InvalidatedTokenStore {

	void invalidate(String token, Instant expiresAt);

	boolean isInvalidated(String token);
}

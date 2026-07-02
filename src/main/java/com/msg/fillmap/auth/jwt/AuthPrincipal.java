package com.msg.fillmap.auth.jwt;

import com.msg.fillmap.user.entity.UserRole;

public record AuthPrincipal(Long userId, UserRole role) {
}

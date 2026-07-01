package com.msg.fillmap.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.msg.fillmap.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmail(String email);

	Optional<User> findByEmail(String email);
}

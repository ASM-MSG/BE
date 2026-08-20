package com.msg.fillmap.event.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.msg.fillmap.event.entity.EventOccurrence;

public interface EventOccurrenceRepository extends JpaRepository<EventOccurrence, Long> {

	Optional<EventOccurrence> findByOccurrenceKey(String occurrenceKey);
}

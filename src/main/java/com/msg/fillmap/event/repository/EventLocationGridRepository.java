package com.msg.fillmap.event.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.msg.fillmap.event.entity.EventLocationGrid;
import com.msg.fillmap.event.entity.EventLocationGridId;

public interface EventLocationGridRepository extends JpaRepository<EventLocationGrid, EventLocationGridId> {

	List<EventLocationGrid> findByIdEventLocationId(Long eventLocationId);
}

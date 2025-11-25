package com.cns.spring_house.repository.workshop;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.model.workshop.Workshop;

public interface WorkshopRespository extends JpaRepository<Workshop, UUID>, JpaSpecificationExecutor<Workshop> {
    
}

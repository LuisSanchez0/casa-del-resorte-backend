package com.cns.spring_house.workshop.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.workshop.entity.Workshop;

public interface WorkshopRepository extends JpaRepository<Workshop, UUID>, JpaSpecificationExecutor<Workshop> {

}

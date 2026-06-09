package com.cns.spring_house.job.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.job.entity.Job;

public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {
    
    boolean existsByWorkshopIdAndProductId(UUID workshop, UUID product);

    Optional<Job> findByIdAndWorkshopId(UUID id, UUID workshopId);

}

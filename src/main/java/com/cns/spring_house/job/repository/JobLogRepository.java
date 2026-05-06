package com.cns.spring_house.job.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.job.entity.JobLog;

public interface JobLogRepository extends JpaRepository<JobLog, UUID>, JpaSpecificationExecutor<JobLog> {
    
}

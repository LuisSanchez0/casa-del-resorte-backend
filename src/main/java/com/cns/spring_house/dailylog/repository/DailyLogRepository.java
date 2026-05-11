package com.cns.spring_house.dailylog.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.dailylog.entity.DailyLog;

public interface DailyLogRepository extends JpaRepository<DailyLog, UUID>, JpaSpecificationExecutor<DailyLog> {
    
}

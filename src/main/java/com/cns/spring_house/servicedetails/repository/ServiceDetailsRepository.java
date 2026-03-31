package com.cns.spring_house.servicedetails.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cns.spring_house.servicedetails.entity.ServiceDetails;

public interface ServiceDetailsRepository extends JpaRepository<ServiceDetails, UUID> {
    
    boolean existsByWorkshopIdAndProductId(UUID workshop, UUID product);

}

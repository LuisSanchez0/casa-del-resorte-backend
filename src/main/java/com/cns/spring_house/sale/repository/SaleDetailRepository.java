package com.cns.spring_house.sale.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cns.spring_house.sale.entity.SaleDetail;

public interface SaleDetailRepository extends JpaRepository<SaleDetail, UUID> {
    
}

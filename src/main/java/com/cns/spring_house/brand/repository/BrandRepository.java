package com.cns.spring_house.brand.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.brand.entity.Brand;

public interface BrandRepository extends JpaRepository<Brand, UUID>, JpaSpecificationExecutor<Brand> {
    
    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, UUID id);


}

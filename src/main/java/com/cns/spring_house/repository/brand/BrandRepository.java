package com.cns.spring_house.repository.brand;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.model.brand.Brand;

public interface BrandRepository extends JpaRepository<Brand, UUID>, JpaSpecificationExecutor<Brand> {
    
    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, UUID id);


}

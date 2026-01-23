package com.cns.spring_house.family.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.family.entity.Family;

public interface FamilyRepository extends JpaRepository<Family, UUID>, JpaSpecificationExecutor<Family> {
    
    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

}

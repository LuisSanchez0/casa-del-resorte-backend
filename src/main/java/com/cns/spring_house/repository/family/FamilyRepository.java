package com.cns.spring_house.repository.family;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.model.family.Family;

public interface FamilyRepository extends JpaRepository<Family, UUID>, JpaSpecificationExecutor<Family> {
    
    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

}

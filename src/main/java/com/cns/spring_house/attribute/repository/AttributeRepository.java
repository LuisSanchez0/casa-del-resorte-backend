package com.cns.spring_house.attribute.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.attribute.entity.Attribute;

public interface AttributeRepository extends JpaRepository<Attribute, UUID>, JpaSpecificationExecutor<Attribute> {
    
    boolean existsByNameAndFamilyId(String name, UUID family);

    boolean existsByNameAndFamilyIdAndIdNot(String name, UUID family, UUID id);

}

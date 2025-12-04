package com.cns.spring_house.repository.model;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.model.model.Model;

public interface ModelRepository extends JpaRepository<Model, UUID>, JpaSpecificationExecutor<Model> { 

    boolean existsByNameAndYearAndBrandId(String name, Integer year, UUID brand);

    boolean existsByNameAndYearAndBrandIdAndIdNot(String name, Integer year, UUID brand, UUID id);

}

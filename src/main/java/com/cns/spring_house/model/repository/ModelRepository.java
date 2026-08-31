package com.cns.spring_house.model.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.model.entity.Model;

public interface ModelRepository extends JpaRepository<Model, UUID>, JpaSpecificationExecutor<Model> { 

    boolean existsByNameAndYearFromAndYearToAndBrandId(
            String name, Integer yearFrom, Integer yearTo, UUID brand);

    boolean existsByNameAndYearFromAndYearToAndBrandIdAndIdNot(
            String name, Integer yearFrom, Integer yearTo, UUID brand, UUID id);

}

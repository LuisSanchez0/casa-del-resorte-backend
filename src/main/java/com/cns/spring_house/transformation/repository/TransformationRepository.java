package com.cns.spring_house.transformation.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cns.spring_house.transformation.entity.Transformation;

public interface TransformationRepository extends JpaRepository<Transformation, UUID> {
    
}

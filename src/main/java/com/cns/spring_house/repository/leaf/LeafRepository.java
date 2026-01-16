package com.cns.spring_house.repository.leaf;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.model.leaf.Leaf;

public interface LeafRepository extends JpaRepository<Leaf, UUID>, JpaSpecificationExecutor<Leaf> {
    
}

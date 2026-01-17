package com.cns.spring_house.repository.leafpack;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.model.leafpack.LeafPack;

public interface LeafPackRepository extends JpaRepository<LeafPack, UUID>, JpaSpecificationExecutor<LeafPack> {
    boolean existsByProductIdAndWorkshopId(UUID product, UUID workshop);

    boolean existsByProductIdAndWorkshopIdAndIdNot(UUID product, UUID workshop, UUID id);
}

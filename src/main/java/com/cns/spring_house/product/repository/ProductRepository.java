package com.cns.spring_house.product.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.cns.spring_house.product.entity.Product;


public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
    
    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);

    @Query(nativeQuery = true, value = """
        SELECT *
        from product p
        WHERE p.family_id = :familyId
            AND p.model_id = :modelId
            AND p.attributes = cast(:attributes as jsonb)
    """)
    Optional<Product> findTargetProduct(UUID familyId, UUID modelId, String attributes);

}

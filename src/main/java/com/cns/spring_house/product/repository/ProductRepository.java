package com.cns.spring_house.product.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.product.entity.Product;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
    
    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);

}

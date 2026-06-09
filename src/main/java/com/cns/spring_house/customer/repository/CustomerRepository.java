package com.cns.spring_house.customer.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.customer.entity.Customer;

public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {
    
    boolean existsByNit(String nit);

    boolean existsByNitAndIdNot(String nit, UUID id);

}

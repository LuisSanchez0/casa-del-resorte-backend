package com.cns.spring_house.employee.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.employee.entity.Employee;

public interface EmployeeRepository extends JpaRepository<Employee, UUID>, JpaSpecificationExecutor<Employee> {

    boolean existsByDpi(String dpi);

    boolean existsByDpiAndIdNot(String dpi, UUID id);

}

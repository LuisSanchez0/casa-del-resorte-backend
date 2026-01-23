package com.cns.spring_house.role.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.role.entity.Role;

public interface RoleRepository extends JpaRepository<Role, UUID>, JpaSpecificationExecutor<Role> {

}

package com.cns.spring_house.useraccount.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.useraccount.entity.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID>, JpaSpecificationExecutor<UserAccount> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByUsernameAndIdNot(String username, UUID id);

    boolean existsByEmployeeId(UUID employeeId);

}

package com.cns.spring_house.service.useraccount;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.dto.useraccount.CreateUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.SearchUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.UpdateUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.UserAccountResponseDTO;
import com.cns.spring_house.exception.EntityAlreadyExistsException;
import com.cns.spring_house.exception.EntityNotFoundException;
import com.cns.spring_house.mapper.useraccount.UserAccountMapper;
import com.cns.spring_house.model.employee.Employee;
import com.cns.spring_house.model.role.Role;
import com.cns.spring_house.model.useraccount.UserAccount;
import com.cns.spring_house.repository.employee.EmployeeRepository;
import com.cns.spring_house.repository.role.RoleRepository;
import com.cns.spring_house.repository.useraccount.UserAccountRepository;
import com.cns.spring_house.repository.useraccount.UserAccountSpecs;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class UserAccountServiceImpl implements UserAccountService {

    private final UserAccountRepository userAccountRepository;
    private final EmployeeRepository employeeRepository;
    private final RoleRepository roleRepository;
    private final UserAccountMapper mapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public List<UserAccountResponseDTO> getAll(
            SearchUserAccountRequestDTO requestDTO) {
        Specification<UserAccount> specs = Specification.allOf(
                UserAccountSpecs.hasActive(requestDTO.active()),
                UserAccountSpecs.hasEmployee(requestDTO.employee()),
                UserAccountSpecs.hasRole(requestDTO.role()),
                UserAccountSpecs.usernameContains(requestDTO.username()));
        return userAccountRepository
                .findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public UserAccountResponseDTO get(UUID id) {
        UserAccount userAccount = userAccountRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la cuenta de usuario solicitada"));
        return mapper.toResponse(userAccount);
    }

    @Override
    public UserAccountResponseDTO create(CreateUserAccountRequestDTO requestDTO) {
        if (userAccountRepository.existsByEmployeeId(requestDTO.employee())) {
            throw new EntityAlreadyExistsException(
                    "El empleado especificado ya tiene una cuenta de usuario");
        }
        if (userAccountRepository.existsByUsername(requestDTO.username())) {
            throw new EntityAlreadyExistsException(
                    "Ya existe una cuenta de usuario con ese nombre");
        }
        UserAccount userAccount = mapper.toUserAccount(requestDTO);
        Employee employee = employeeRepository
                .findById(requestDTO.employee())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el empleado especificado"));
        userAccount.setEmployee(employee);
        Role role = roleRepository
                .findById(requestDTO.role())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el role especificado"));
        userAccount.setRole(role);
        userAccount.setPassword(passwordEncoder.encode(requestDTO.password()));
        userAccount = userAccountRepository.save(userAccount);
        return mapper.toResponse(userAccount);
    }

    @Override
    public UserAccountResponseDTO update(UUID id, UpdateUserAccountRequestDTO requestDTO) {
        UserAccount userAccount = userAccountRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la cuenta de usuario especificada"));
        if (requestDTO.role() != null && !requestDTO.role().equals(userAccount.getRole().getId())) {
            Role role = roleRepository
                    .findById(requestDTO.role())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No existe el role especificado"));
            userAccount.setRole(role);
        }
        if (requestDTO.password() != null) {
            String newPassword = passwordEncoder.encode(requestDTO.password());
            userAccount.setPassword(newPassword);
        }
        if (requestDTO.username() != null && userAccountRepository
                .existsByUsernameAndIdNot(requestDTO.username(), id)) {
            throw new EntityAlreadyExistsException(
                    "Ya existe otra cuenta de usuario con ese nombre");
        }
        mapper.updateUserAccountFromDTO(requestDTO, userAccount);
        userAccount = userAccountRepository.save(userAccount);
        return mapper.toResponse(userAccount);
    }

}

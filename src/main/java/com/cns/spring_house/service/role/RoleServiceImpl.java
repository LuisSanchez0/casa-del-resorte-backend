package com.cns.spring_house.service.role;

import java.util.List;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.cns.spring_house.dto.role.RoleResponseDTO;
import com.cns.spring_house.dto.role.SearchRoleRequestDTO;
import com.cns.spring_house.mapper.role.RoleMapper;
import com.cns.spring_house.model.role.Role;
import com.cns.spring_house.repository.role.RoleRepository;
import com.cns.spring_house.repository.role.RoleSpecs;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleRepository repository;
    private final RoleMapper mapper;

    @Override
    public List<RoleResponseDTO> getAll(SearchRoleRequestDTO requestDTO) {
        Specification<Role> specs = Specification.allOf(
                RoleSpecs.nameContains(requestDTO.name()));
        return repository
                .findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

}

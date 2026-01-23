package com.cns.spring_house.role.service;

import java.util.List;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.role.dto.RoleResponseDTO;
import com.cns.spring_house.role.dto.SearchRoleRequestDTO;
import com.cns.spring_house.role.mapper.RoleMapper;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.role.repository.RoleSpecs;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
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

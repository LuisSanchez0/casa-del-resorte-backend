package com.cns.spring_house.role.service;

import java.util.List;

import com.cns.spring_house.role.dto.RoleResponseDTO;
import com.cns.spring_house.role.dto.SearchRoleRequestDTO;

public interface RoleService {

    List<RoleResponseDTO> getAll(SearchRoleRequestDTO requestDTO);

}

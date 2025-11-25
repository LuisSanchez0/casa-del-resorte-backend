package com.cns.spring_house.service.role;

import java.util.List;

import com.cns.spring_house.dto.role.RoleResponseDTO;
import com.cns.spring_house.dto.role.SearchRoleRequestDTO;

public interface RoleService {

    List<RoleResponseDTO> getAll(SearchRoleRequestDTO requestDTO);

}

package com.cns.spring_house.mapper.role;

import org.mapstruct.Mapper;

import com.cns.spring_house.dto.role.RoleResponseDTO;
import com.cns.spring_house.model.role.Role;

@Mapper(componentModel = "spring")
public interface RoleMapper {

    RoleResponseDTO toResponse(Role role);

}

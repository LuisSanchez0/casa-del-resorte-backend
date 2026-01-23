package com.cns.spring_house.role.mapper;

import org.mapstruct.Mapper;

import com.cns.spring_house.role.dto.RoleResponseDTO;
import com.cns.spring_house.role.entity.Role;

@Mapper(componentModel = "spring")
public interface RoleMapper {

    RoleResponseDTO toResponse(Role role);

}

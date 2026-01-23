package com.cns.spring_house.useraccount.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.useraccount.dto.CreateUserAccountRequestDTO;
import com.cns.spring_house.useraccount.dto.UpdateUserAccountRequestDTO;
import com.cns.spring_house.useraccount.dto.UserAccountResponseDTO;
import com.cns.spring_house.useraccount.entity.UserAccount;

@Mapper(componentModel = "spring")
public interface UserAccountMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "isActive", ignore = true)
    @Mapping(target = "employee", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "password", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    UserAccount toUserAccount(CreateUserAccountRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "employee", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "password", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateUserAccountFromDTO(UpdateUserAccountRequestDTO requestDTO, @MappingTarget UserAccount userAccount);

    UserAccountResponseDTO toResponse(UserAccount userAccount);

}

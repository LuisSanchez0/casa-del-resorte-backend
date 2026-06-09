package com.cns.spring_house.customer.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.MappingConstants.ComponentModel;

import com.cns.spring_house.customer.dto.CreateCustomerRequestDTO;
import com.cns.spring_house.customer.dto.CustomerResponseDTO;
import com.cns.spring_house.customer.dto.UpdateCustomerRequestDTO;
import com.cns.spring_house.customer.entity.Customer;

@Mapper(componentModel = ComponentModel.SPRING)
public interface CustomerMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "name", qualifiedByName = "toUpperCase")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Customer toCustomer(CreateCustomerRequestDTO requestDTO);

    CustomerResponseDTO toResponse(Customer customer);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "name", qualifiedByName = "toUpperCase")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateCustomer(UpdateCustomerRequestDTO requestDTO, @MappingTarget Customer customer);

    @Named("toUpperCase")
    default String toUpperCase(String value) {
        return value == null ? null : value.toUpperCase();
    }

}

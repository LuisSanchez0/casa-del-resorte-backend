package com.cns.spring_house.servicedetails.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.servicedetails.dto.CreateServiceDetailsRequestDTO;
import com.cns.spring_house.servicedetails.dto.ServiceDetailsResponseDTO;
import com.cns.spring_house.servicedetails.dto.UpdateServiceDetailsRequestDTO;
import com.cns.spring_house.servicedetails.entity.ServiceDetails;

@Mapper(componentModel = ComponentModel.SPRING)
public interface ServiceDetailsMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    ServiceDetails toServiceDetails(CreateServiceDetailsRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateServiceFromDTO(UpdateServiceDetailsRequestDTO requestDTO, @MappingTarget ServiceDetails service);

    ServiceDetailsResponseDTO toResponse(ServiceDetails serviceDetails);

}

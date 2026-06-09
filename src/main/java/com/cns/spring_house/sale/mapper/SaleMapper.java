package com.cns.spring_house.sale.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants.ComponentModel;

import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.entity.Sale;

@Mapper(componentModel = ComponentModel.SPRING)
public interface SaleMapper {

    SaleResponseDTO toResponse(Sale sale);

}

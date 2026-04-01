package com.cns.spring_house.vehiclemovement.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;

import com.cns.spring_house.vehiclemovement.dto.CreateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.VehicleMovementResponseDTO;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;

@Mapper(componentModel = ComponentModel.SPRING)
public interface VehicleMovementMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore = true)
    @Mapping(target = "entry", ignore = true)
    @Mapping(target = "exit", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    VehicleMovement toVehicleMovement(CreateVehicleMovementRequestDTO requestDTO);

    VehicleMovementResponseDTO toResponse(VehicleMovement movement);

}

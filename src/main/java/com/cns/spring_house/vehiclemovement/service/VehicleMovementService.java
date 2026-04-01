package com.cns.spring_house.vehiclemovement.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.vehiclemovement.dto.CreateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.SearchVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.VehicleMovementResponseDTO;

public interface VehicleMovementService {
    
    List<VehicleMovementResponseDTO> getAll(SearchVehicleMovementRequestDTO requestDTO);
    VehicleMovementResponseDTO get(UUID id);
    VehicleMovementResponseDTO create(CreateVehicleMovementRequestDTO requestDTO);
    VehicleMovementResponseDTO registerExit(UUID id);

}

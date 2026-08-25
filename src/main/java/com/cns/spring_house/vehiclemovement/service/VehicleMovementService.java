package com.cns.spring_house.vehiclemovement.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.vehiclemovement.dto.CreateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.PhoneSuggestionRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.PhoneSuggestionResponseDTO;
import com.cns.spring_house.vehiclemovement.dto.SearchVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.UpdateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.VehicleMovementResponseDTO;

public interface VehicleMovementService {

    List<VehicleMovementResponseDTO> getAll(SearchVehicleMovementRequestDTO requestDTO);
    VehicleMovementResponseDTO get(UUID id);
    VehicleMovementResponseDTO create(CreateVehicleMovementRequestDTO requestDTO);
    VehicleMovementResponseDTO update(UUID id, UpdateVehicleMovementRequestDTO requestDTO);
    VehicleMovementResponseDTO registerExit(UUID id);

    /**
     * Telefono que se propondra al abrir una bitacora nueva para esa placa, o una
     * respuesta con {@code phone == null} cuando no hay ninguno. Nunca lo aplica
     * por su cuenta: quien decide reutilizarlo es el operador.
     */
    PhoneSuggestionResponseDTO getPhoneSuggestion(PhoneSuggestionRequestDTO requestDTO);

}

package com.cns.spring_house.vehiclemovement.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.vehiclemovement.dto.CreateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.SearchVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.VehicleMovementResponseDTO;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;
import com.cns.spring_house.vehiclemovement.mapper.VehicleMovementMapper;
import com.cns.spring_house.vehiclemovement.repository.VehicleMovementRepository;
import com.cns.spring_house.vehiclemovement.repository.VehicleMovementSpecs;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Transactional
@Service
public class VehicleMovementServiceImpl implements VehicleMovementService {
    
    private final VehicleMovementRepository repository;
    private final VehicleMovementMapper mapper;
    private final WorkshopRepository workshopRepository;

    @Override
    public List<VehicleMovementResponseDTO> getAll(SearchVehicleMovementRequestDTO requestDTO) {
        Specification<VehicleMovement> specs = Specification.allOf(
                VehicleMovementSpecs.plateContains(requestDTO.plate()),
                VehicleMovementSpecs.hasWorkshop(requestDTO.workshop()),
                VehicleMovementSpecs.hasEntry(requestDTO.entry()),
                VehicleMovementSpecs.hasExit(requestDTO.exit()));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public VehicleMovementResponseDTO get(UUID id) {
        VehicleMovement movement = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el registro especificado"));
        return mapper.toResponse(movement);
    }

    @Override
    public VehicleMovementResponseDTO create(CreateVehicleMovementRequestDTO requestDTO) {
        boolean exists = repository
                .existsByPlateAndExitIsNull(requestDTO.plate());
        if (exists) {
            throw new ValidationException(
                    "No se ha registrado una salida para este vehiculo");
        }
        Workshop workshop = workshopRepository
                .findById(SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
        VehicleMovement movement = mapper.toVehicleMovement(requestDTO);
        movement.setWorkshop(workshop);
        movement = repository.save(movement);
        return mapper.toResponse(movement);
    }

    @Override
    public VehicleMovementResponseDTO registerExit(UUID id) {
        VehicleMovement movement = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el registro especificado"));
        if (movement.getExit() != null) {
            throw new ValidationException(
                    "Ya se marco una salida para este registro");
        }
        movement.setExit(LocalDateTime.now());
        movement = repository.save(movement);
        return mapper.toResponse(movement);
    }

}

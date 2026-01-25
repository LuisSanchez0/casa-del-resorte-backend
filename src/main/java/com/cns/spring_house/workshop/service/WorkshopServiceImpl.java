package com.cns.spring_house.workshop.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.workshop.dto.CreateWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.SearchWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.UpdateWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;
import com.cns.spring_house.workshop.mapper.WorkshopMapper;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;
import com.cns.spring_house.workshop.repository.WorkshopSpecs;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class WorkshopServiceImpl implements WorkshopService {

    private final WorkshopRepository repository;
    private final WorkshopMapper mapper;

    @Override
    public List<WorkshopResponseDTO> getAll(SearchWorkshopRequestDTO requestDTO) {
        Specification<Workshop> specs = Specification.allOf(
                WorkshopSpecs.nameContains(requestDTO.name()),
                WorkshopSpecs.addressContains(requestDTO.address()),
                WorkshopSpecs.hasActive(requestDTO.active()));
        return repository
                .findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public WorkshopResponseDTO get(UUID id) {
        Workshop workshop = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller solicitado"));
        return mapper.toResponse(workshop);
    }

    @Override
    public WorkshopResponseDTO create(CreateWorkshopRequestDTO requestDTO) {
        Workshop workshop = mapper.toWorkshop(requestDTO);
        workshop = repository.save(workshop);
        return mapper.toResponse(workshop);
    }

    @Override
    public WorkshopResponseDTO update(UUID id, UpdateWorkshopRequestDTO requestDTO) {
        Workshop workshop = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
        mapper.updateWorkshopFromDTO(requestDTO, workshop);
        workshop = repository.save(workshop);
        return mapper.toResponse(workshop);
    }

}

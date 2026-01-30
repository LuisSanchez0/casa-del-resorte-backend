package com.cns.spring_house.family.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.dto.CreateFamilyRequestDTO;
import com.cns.spring_house.family.dto.FamilyResponseDTO;
import com.cns.spring_house.family.dto.SearchFamilyRequestDTO;
import com.cns.spring_house.family.dto.UpdateFamilyRequestDTO;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.family.mapper.FamilyMapper;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.family.repository.FamilySpecs;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class FamilyServiceImpl implements FamilyService {

    private final FamilyRepository repository;
    private final FamilyMapper mapper;

    @Override
    public FamilyResponseDTO get(UUID id) {
        Family family = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia solicitada"));
        return mapper.toResponse(family);
    }

    @Override
    public List<FamilyResponseDTO> getAll(SearchFamilyRequestDTO requestDTO) {
        Specification<Family> specs = Specification.allOf(
                FamilySpecs.requiresModel(requestDTO.requiresModel()),
                FamilySpecs.hasCategory(requestDTO.category()),
                Specification.anyOf(
                        FamilySpecs.codeContains(requestDTO.q()),
                        FamilySpecs.nameContains(requestDTO.q())));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public FamilyResponseDTO create(CreateFamilyRequestDTO requestDTO) {
        if (repository.existsByCode(requestDTO.code())) {
            throw new EntityAlreadyExistsException(
                    "Ya existe una familia con el codigo especificado");
        }
        Family family = mapper.toFamily(requestDTO);
        family = repository.save(family);
        return mapper.toResponse(family);
    }

    @Override
    public FamilyResponseDTO update(UUID id, UpdateFamilyRequestDTO requestDTO) {
        Family family = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia solicitada"));
        String code = requestDTO.code();
        if (code != null && !code.equals(family.getCode())) {
            if (repository.existsByCodeAndIdNot(code, id)) {
                throw new EntityAlreadyExistsException(
                        "Ya existe una familia con el codigo especificado");
            }
        }
        mapper.updateFamilyFromDTO(requestDTO, family);
        family = repository.save(family);
        return mapper.toResponse(family);
    }

}

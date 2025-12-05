package com.cns.spring_house.service.family;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.dto.family.CreateFamilyRequestDTO;
import com.cns.spring_house.dto.family.FamilyResponseDTO;
import com.cns.spring_house.dto.family.SearchFamilyRequestDTO;
import com.cns.spring_house.dto.family.UpdateFamilyRequestDTO;
import com.cns.spring_house.exception.EntityAlreadyExistsException;
import com.cns.spring_house.exception.EntityNotFoundException;
import com.cns.spring_house.mapper.family.FamilyMapper;
import com.cns.spring_house.model.family.Family;
import com.cns.spring_house.repository.family.FamilyRepository;
import com.cns.spring_house.repository.family.FamilySpecs;

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

package com.cns.spring_house.attribute.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.attribute.dto.AttributeResponseDTO;
import com.cns.spring_house.attribute.dto.CreateAttributeRequestDTO;
import com.cns.spring_house.attribute.dto.SearchAttributeRequestDTO;
import com.cns.spring_house.attribute.dto.UpdateAttributeRequestDTO;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.attribute.mapper.AttributeMapper;
import com.cns.spring_house.attribute.entity.Attribute;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.attribute.repository.AttributeRepository;
import com.cns.spring_house.attribute.repository.AttributeSpecs;
import com.cns.spring_house.family.repository.FamilyRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class AttributeServiceImpl implements AttributeService {
    
    private final AttributeRepository repository;
    private final AttributeMapper mapper;
    private final FamilyRepository familyRepository;

    @Override
    public AttributeResponseDTO get(UUID id) {
        Attribute attribute = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el atributo solicitado"));
        return mapper.toResponse(attribute);
    }

    @Override
    public List<AttributeResponseDTO> getAll(SearchAttributeRequestDTO requestDTO) {
        Specification<Attribute> specs = Specification.allOf(
                AttributeSpecs.hasDataType(requestDTO.type()),
                AttributeSpecs.hasFamily(requestDTO.family()),
                AttributeSpecs.isRequired(requestDTO.required()),
                AttributeSpecs.nameContains(requestDTO.name()));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public AttributeResponseDTO create(CreateAttributeRequestDTO requestDTO) {
        Family family = familyRepository
                .findById(requestDTO.family())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia solicitada"));
        boolean alreadyExists = repository
                .existsByNameAndFamilyId(requestDTO.name(), requestDTO.family());
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe el atributo para esa familia");
        }
        Attribute attribute = mapper.toAttribute(requestDTO);
        attribute.setFamily(family);
        attribute = repository.save(attribute);
        return mapper.toResponse(attribute);
    }

    @Override
    public AttributeResponseDTO update(UUID id, UpdateAttributeRequestDTO requestDTO) {
        Attribute attribute = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el atributo solicitado"));
        UUID familyId = requestDTO.family();
        if (familyId != null && !familyId.equals(attribute.getFamily().getId())) {
            Family family = familyRepository
                .findById(requestDTO.family())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia solicitada"));
            attribute.setFamily(family);
        }
        mapper.updateAttributeFromDTO(requestDTO, attribute);
        boolean alreadyExists = repository
                .existsByNameAndFamilyIdAndIdNot(
                        attribute.getName(),
                        attribute.getFamily().getId(),
                        id);
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe el atributo para esa familia");
        }
        attribute = repository.save(attribute);
        return mapper.toResponse(attribute);
    }

    @Override
    public void delete(UUID id) {
        Attribute attribute = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el atributo solicitado"));
        repository.delete(attribute);
    }

}

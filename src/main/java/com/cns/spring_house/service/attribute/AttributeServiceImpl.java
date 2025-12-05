package com.cns.spring_house.service.attribute;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.cns.spring_house.dto.attribute.AttributeResponseDTO;
import com.cns.spring_house.dto.attribute.CreateAttributeRequestDTO;
import com.cns.spring_house.dto.attribute.SearchAttributeRequestDTO;
import com.cns.spring_house.dto.attribute.UpdateAttributeRequestDTO;
import com.cns.spring_house.exception.EntityAlreadyExistsException;
import com.cns.spring_house.exception.EntityNotFoundException;
import com.cns.spring_house.mapper.attribute.AttributeMapper;
import com.cns.spring_house.model.attribute.Attribute;
import com.cns.spring_house.model.family.Family;
import com.cns.spring_house.repository.attribute.AttributeRepository;
import com.cns.spring_house.repository.attribute.AttributeSpecs;
import com.cns.spring_house.repository.family.FamilyRepository;

import lombok.RequiredArgsConstructor;

@Service
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
                AttributeSpecs.hasRequired(requestDTO.required()),
                AttributeSpecs.keyContains(requestDTO.key()));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public AttributeResponseDTO create(CreateAttributeRequestDTO requestDTO) {
        boolean alreadyExists = repository
                .existsByKeyAndFamilyId(requestDTO.key(), requestDTO.family()); 
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe el atributo para esa familia");
        }
        Family family = familyRepository
                .findById(requestDTO.family())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia solicitada"));
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
                .existsByKeyAndFamilyIdAndIdNot(
                        attribute.getKey(),
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

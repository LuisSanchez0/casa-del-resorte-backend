package com.cns.spring_house.family.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.dto.CreateAttributeRequestDTO;
import com.cns.spring_house.family.dto.CreateFamilyRequestDTO;
import com.cns.spring_house.family.dto.FamilyResponseDTO;
import com.cns.spring_house.family.dto.SearchFamilyRequestDTO;
import com.cns.spring_house.family.dto.UpdateAttributeRequestDTO;
import com.cns.spring_house.family.dto.UpdateFamilyRequestDTO;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.family.mapper.AttributeMapper;
import com.cns.spring_house.family.mapper.FamilyMapper;
import com.cns.spring_house.family.entity.Attribute;
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
    private final AttributeMapper attributeMapper;

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

    /**
     * Las familias en las que una pieza de esta familia puede convertirse al
     * transformarse: la suya y las que {@link TransformationFamilyCompatibility}
     * declara compatibles.
     *
     * Existe para que el cliente no tenga que conocer ningun codigo ni ningun
     * nombre de familia para construir su buscador de producto de salida:
     * pregunta por los destinos de la familia que tiene delante y busca en esos.
     * La lista NO es una autorizacion —lo que se acepte de verdad lo decide
     * `SpecialTransformationHandler` al crear la transformacion, contra esta
     * misma regla—, solo evita ofrecer lo que luego se iba a rechazar.
     *
     * Se resuelve sobre el catalogo entero en UNA consulta: la tabla `family`
     * tiene unas pocas filas y una consulta por destino seria un N+1 para
     * ahorrar nada.
     */
    @Override
    public List<FamilyResponseDTO> getTransformationTargets(UUID id) {
        Family family = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia solicitada"));
        return TransformationFamilyCompatibility
                .targetsOf(family, repository.findAll())
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

    @Override
    public FamilyResponseDTO createAttribute(
            UUID id, CreateAttributeRequestDTO requestDTO) {
        Family family = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia solicitada"));
        validateDuplicateName(family, requestDTO.name(), null);
        Attribute attribute = attributeMapper.toAttribute(requestDTO);
        family.addAttribute(attribute);
        family = repository.save(family);
        return mapper.toResponse(family);
    }

    @Override
    public FamilyResponseDTO updateAttribute(
            UUID id, UUID attrId, UpdateAttributeRequestDTO requestDTO) {
        Family family = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la familia solicitada"));
        validateDuplicateName(family, requestDTO.name(), attrId);
        Attribute attribute = family.getAttributes().stream()
                .filter(attr -> attr.getId().equals(attrId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el atributo especificado"));
        attributeMapper.updateAttributeFromDTO(requestDTO, attribute);
        family = repository.save(family);
        return mapper.toResponse(family);
    }

    private void validateDuplicateName(Family family, String name, UUID excludeId) {
        family.getAttributes().forEach(attr -> {
            boolean exists = attr.getName().equals(name)
                    && (excludeId == null || !attr.getId().equals(excludeId));
            if (exists) {
                throw new EntityAlreadyExistsException(
                        "Ya existe un atributo con ese nombre");
            }
        });
    }

}

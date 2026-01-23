package com.cns.spring_house.model.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.model.dto.CreateModelRequestDTO;
import com.cns.spring_house.model.dto.ModelResponseDTO;
import com.cns.spring_house.model.dto.SearchModelRequestDTO;
import com.cns.spring_house.model.dto.UpdateModelRequestDTO;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.model.mapper.ModelMapper;
import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.brand.repository.BrandRepository;
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.model.repository.ModelSpecs;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class ModelServiceImpl implements ModelService {
    
    private final ModelMapper mapper;
    private final ModelRepository repository;
    private final BrandRepository brandRepository;

    @Override
    public ModelResponseDTO get(UUID id) {
        Model model = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el modelo solicitado"));
        return mapper.toResponse(model);
    }

    @Override
    public List<ModelResponseDTO> getAll(SearchModelRequestDTO requestDTO) {
        Specification<Model> specs = Specification.allOf(
                ModelSpecs.hasBrand(requestDTO.brand()),
                ModelSpecs.hasYear(requestDTO.year()),
                ModelSpecs.nameContains(requestDTO.name()));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public ModelResponseDTO create(CreateModelRequestDTO requestDTO) {
        boolean alreadyExists = repository
                .existsByNameAndYearAndBrandId(
                        requestDTO.name(),
                        requestDTO.year(),
                        requestDTO.brand());
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un modelo para esa marca con el mismo nombre y año");
        }
        Model model = mapper.toModel(requestDTO);
        Brand brand = brandRepository
                .findById(requestDTO.brand())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la marca especificada"));
        model.setBrand(brand);
        model = repository.save(model);
        return mapper.toResponse(model);
    }

    @Override
    public ModelResponseDTO update(UUID id, UpdateModelRequestDTO requestDTO) {
        Model model = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el modelo solicitado"));
        UUID brandId = requestDTO.brand();
        mapper.updateModelFromDTO(requestDTO, model);
        if (brandId != null && !brandId.equals(model.getBrand().getId())) {
            Brand brand = brandRepository
                .findById(requestDTO.brand())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la marca especificada"));
            model.setBrand(brand);
        }
        boolean alreadyExists = repository
                .existsByNameAndYearAndBrandIdAndIdNot(
                        model.getName(),
                        model.getYear(),
                        model.getBrand().getId(),
                        id);
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un modelo para esa marca con el mismo nombre y año");
        }
        model = repository.save(model);
        return mapper.toResponse(model);
    }

}

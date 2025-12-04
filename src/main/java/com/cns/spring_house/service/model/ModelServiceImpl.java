package com.cns.spring_house.service.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.dto.model.CreateModelRequestDTO;
import com.cns.spring_house.dto.model.ModelResponseDTO;
import com.cns.spring_house.dto.model.SearchModelRequestDTO;
import com.cns.spring_house.dto.model.UpdateModelRequestDTO;
import com.cns.spring_house.exception.EntityAlreadyExistsException;
import com.cns.spring_house.exception.EntityNotFoundException;
import com.cns.spring_house.mapper.model.ModelMapper;
import com.cns.spring_house.model.brand.Brand;
import com.cns.spring_house.model.model.Model;
import com.cns.spring_house.repository.brand.BrandRepository;
import com.cns.spring_house.repository.model.ModelRepository;
import com.cns.spring_house.repository.model.ModelSpecs;

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

package com.cns.spring_house.model.service;

import java.util.List;
import java.util.Map;
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
import com.cns.spring_house.shared.exception.ValidationException;
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
    
    private static final String YEAR_ORDER_MESSAGE =
            "El año final no puede ser menor que el año inicial";

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
                ModelSpecs.coversYear(requestDTO.year()),
                ModelSpecs.nameContains(requestDTO.name()));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public ModelResponseDTO create(CreateModelRequestDTO requestDTO) {
        validateYearRange(requestDTO.yearFrom(), requestDTO.yearTo());
        boolean alreadyExists = repository
                .existsByNameAndYearFromAndYearToAndBrandId(
                        requestDTO.name(),
                        requestDTO.yearFrom(),
                        requestDTO.yearTo(),
                        requestDTO.brand());
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un modelo para esa marca con el mismo nombre y años");
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
        // Los extremos se comprueban YA FUSIONADOS con lo que la fila tenía —un
        // PATCH que solo manda `yearTo` puede dejar el intervalo al revés sin
        // que el valor enviado tenga nada de raro por sí solo— y ANTES de tocar
        // la entidad: rechazar después dejaría la fila ya modificada en el
        // contexto de persistencia.
        validateYearRange(
                merged(requestDTO.yearFrom(), model.getYearFrom()),
                merged(requestDTO.yearTo(), model.getYearTo()));
        mapper.updateModelFromDTO(requestDTO, model);
        if (brandId != null && !brandId.equals(model.getBrand().getId())) {
            Brand brand = brandRepository
                .findById(requestDTO.brand())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la marca especificada"));
            model.setBrand(brand);
        }
        boolean alreadyExists = repository
                .existsByNameAndYearFromAndYearToAndBrandIdAndIdNot(
                        model.getName(),
                        model.getYearFrom(),
                        model.getYearTo(),
                        model.getBrand().getId(),
                        id);
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un modelo para esa marca con el mismo nombre y años");
        }
        model = repository.save(model);
        return mapper.toResponse(model);
    }

    /**
     * El único invariante del intervalo, y el mismo que respalda el CHECK de la
     * tabla. Se señala sobre `yearTo` porque es el campo que el formulario
     * puede corregir sin cambiar la intención de quien lo llenó.
     */
    /** Lo que el PATCH manda, o lo que la fila ya tenía si no lo manda. */
    private Integer merged(Integer requested, Integer current) {
        return requested != null ? requested : current;
    }

    private void validateYearRange(Integer yearFrom, Integer yearTo) {
        if (yearFrom == null || yearTo == null || yearFrom <= yearTo) {
            return;
        }
        throw new ValidationException(
                YEAR_ORDER_MESSAGE, Map.of("yearTo", YEAR_ORDER_MESSAGE));
    }

}

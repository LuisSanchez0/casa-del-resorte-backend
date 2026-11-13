package com.cns.spring_house.model.service;

import java.time.Clock;
import java.time.LocalDateTime;
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
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.model.repository.ModelSpecs;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class ModelServiceImpl implements ModelService {
    
    private static final String YEAR_ORDER_MESSAGE =
            "El año final no puede ser menor que el año inicial";

    private static final String NOT_FOUND_MESSAGE = "No existe el modelo solicitado";

    private static final String DUPLICATE_MESSAGE =
            "Ya existe un modelo para esa marca con el mismo nombre y años";

    private static final String DUPLICATE_DELETED_MESSAGE =
            "Ya existió un modelo para esa marca con el mismo nombre y años y fue eliminado: "
            + "no se puede volver a crear";

    private final ModelMapper mapper;
    private final ModelRepository repository;
    /** La marca de un modelo nuevo o movido tiene que estar vigente. */
    private final CatalogSelection catalogSelection;
    private final Clock clock;

    /**
     * Solo un modelo ELEGIBLE: eliminado, o de una marca eliminada, responde como
     * inexistente. El historial no lo pide aqui: un producto trae su modelo
     * dentro de su propia respuesta.
     */
    @Override
    public ModelResponseDTO get(UUID id) {
        Model model = repository
                .findById(id)
                .filter(Model::isSelectable)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
        return mapper.toResponse(model);
    }

    @Override
    public List<ModelResponseDTO> getAll(SearchModelRequestDTO requestDTO) {
        Specification<Model> specs = Specification.allOf(
                ModelSpecs.isSelectable(),
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
        Brand brand = catalogSelection.requireSelectableBrand(requestDTO.brand());
        rejectTakenIdentity(
                requestDTO.name(), requestDTO.yearFrom(), requestDTO.yearTo(),
                brand.getId(), null);
        Model model = mapper.toModel(requestDTO);
        model.setBrand(brand);
        model = repository.save(model);
        return mapper.toResponse(model);
    }

    @Override
    public ModelResponseDTO update(UUID id, UpdateModelRequestDTO requestDTO) {
        // FOR UPDATE: una eliminacion simultanea espera o ya se ve, y el PATCH
        // nunca guarda encima una copia vigente. Un modelo eliminado —o de una
        // marca eliminada— no se edita: responde como inexistente.
        Model model = repository
                .findByIdForUpdate(id)
                .filter(Model::isSelectable)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
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
            model.setBrand(catalogSelection.requireSelectableBrand(brandId));
        }
        rejectTakenIdentity(
                model.getName(), model.getYearFrom(), model.getYearTo(),
                model.getBrand().getId(), id);
        model = repository.save(model);
        return mapper.toResponse(model);
    }

    /**
     * Eliminacion LOGICA y definitiva. Solo estampa el modelo: no toca a su
     * marca, a los otros modelos de la marca, ni a los productos, inventario o
     * ventas que lo usan. Un modelo ya eliminado se acepta sin cambios y
     * conserva su fecha original; uno inexistente responde 404.
     */
    @Override
    public void delete(UUID id) {
        Model model = repository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
        if (model.isDeleted()) {
            return;
        }
        model.markDeleted(LocalDateTime.now(clock));
        repository.save(model);
    }

    /**
     * La UNIQUE (marca, nombre, años) cubre tambien los modelos eliminados: la
     * combinacion no se libera ni un alta la reactiva.
     */
    private void rejectTakenIdentity(
            String name, Integer yearFrom, Integer yearTo, UUID brandId, UUID ownId) {
        repository.findByNameAndYearFromAndYearToAndBrandId(name, yearFrom, yearTo, brandId)
                .filter(existing -> !existing.getId().equals(ownId))
                .ifPresent(existing -> {
                    throw new EntityAlreadyExistsException(existing.isDeleted()
                            ? DUPLICATE_DELETED_MESSAGE
                            : DUPLICATE_MESSAGE);
                });
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

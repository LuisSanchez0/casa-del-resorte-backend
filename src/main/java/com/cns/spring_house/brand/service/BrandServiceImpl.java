package com.cns.spring_house.brand.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.brand.dto.BrandResponseDTO;
import com.cns.spring_house.brand.dto.CreateBrandRequestDTO;
import com.cns.spring_house.brand.dto.SearchBrandRequestDTO;
import com.cns.spring_house.brand.dto.UpdateBrandRequestDTO;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.brand.mapper.BrandMapper;
import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.brand.repository.BrandRepository;
import com.cns.spring_house.brand.repository.BrandSpecs;
import com.cns.spring_house.s3.service.S3Service;

import lombok.RequiredArgsConstructor;

/**
 * Una marca eliminada desaparece de todo lo operativo —listado, busqueda,
 * detalle y edicion responden como si no existiera— pero su fila se conserva:
 * sus modelos, y a traves de ellos productos, ventas y cierres, la siguen
 * leyendo dentro de su propio historial.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    private static final String NOT_FOUND_MESSAGE = "No existe la marca solicitada";

    private static final String DUPLICATE_MESSAGE = "Ya existe una marca con el mismo nombre";

    private static final String DUPLICATE_DELETED_MESSAGE =
            "Ya existió una marca con ese nombre y fue eliminada: el nombre no se puede volver a usar";

    private final BrandMapper mapper;
    private final BrandRepository repository;
    private final S3Service s3Service;
    private final Clock clock;

    @Override
    public BrandResponseDTO get(UUID id) {
        Brand brand = repository
                .findById(id)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
        return mapper.toResponse(brand);
    }

    @Override
    public List<BrandResponseDTO> getAll(SearchBrandRequestDTO requestDTO) {
        Specification<Brand> specs = Specification.allOf(
                BrandSpecs.isActive(),
                BrandSpecs.nameContains(requestDTO.name()));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    /**
     * Un alta con el nombre de una marca eliminada se rechaza: no la reactiva ni
     * crea una segunda con la misma identidad visible.
     */
    @Override
    public BrandResponseDTO create(CreateBrandRequestDTO requestDTO) {
        rejectTakenName(requestDTO.name(), null);
        Brand brand = mapper.toBrand(requestDTO);
        String logoKey = s3Service.uploadImage(requestDTO.logo());
        brand.setLogoKey(logoKey);
        brand = repository.save(brand);
        return mapper.toResponse(brand);
    }

    /**
     * Bajo FOR UPDATE: si la marca se elimina mientras se edita, el PATCH espera
     * y la encuentra eliminada, en vez de guardar encima la copia vigente.
     */
    @Override
    public BrandResponseDTO update(UUID id, UpdateBrandRequestDTO requestDTO) {
        Brand brand = repository
                .findByIdForUpdate(id)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
        String newName = requestDTO.name();
        if (newName != null && !newName.equals(brand.getName())) {
            rejectTakenName(newName, id);
        }
        mapper.updateBrandFromDTO(requestDTO, brand);
        if (requestDTO.logo() != null) {
            String currentLogoKey = brand.getLogoKey();
            String newLogoKey = s3Service.uploadImage(requestDTO.logo());
            brand.setLogoKey(newLogoKey);
            s3Service.deleteObject(currentLogoKey);
        }
        brand = repository.save(brand);
        return mapper.toResponse(brand);
    }

    /**
     * Solo estampa la eliminacion. No toca a sus modelos —dejan de ser elegibles
     * por la marca, ver ModelSpecs#isSelectable—, ni a productos, inventario o
     * ventas, ni el logo en S3, que el historial puede seguir mostrando.
     *
     * Una marca ya eliminada se acepta sin cambios (reintento, doble clic u otra
     * pestaña): la fecha original se conserva.
     */
    @Override
    public void delete(UUID id) {
        Brand brand = repository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
        if (brand.isDeleted()) {
            return;
        }
        brand.markDeleted(LocalDateTime.now(clock));
        repository.save(brand);
    }

    private void rejectTakenName(String name, UUID ownId) {
        repository.findByName(name)
                .filter(existing -> !existing.getId().equals(ownId))
                .ifPresent(existing -> {
                    throw new EntityAlreadyExistsException(existing.isDeleted()
                            ? DUPLICATE_DELETED_MESSAGE
                            : DUPLICATE_MESSAGE);
                });
    }

}

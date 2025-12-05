package com.cns.spring_house.service.brand;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.dto.brand.BrandResponseDTO;
import com.cns.spring_house.dto.brand.CreateBrandRequestDTO;
import com.cns.spring_house.dto.brand.SearchBrandRequestDTO;
import com.cns.spring_house.dto.brand.UpdateBrandRequestDTO;
import com.cns.spring_house.exception.EntityAlreadyExistsException;
import com.cns.spring_house.exception.EntityNotFoundException;
import com.cns.spring_house.mapper.brand.BrandMapper;
import com.cns.spring_house.model.brand.Brand;
import com.cns.spring_house.repository.brand.BrandRepository;
import com.cns.spring_house.repository.brand.BrandSpecs;
import com.cns.spring_house.service.s3.S3Service;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    private final BrandMapper mapper;
    private final BrandRepository repository;
    private final S3Service s3Service;

    @Override
    public BrandResponseDTO get(UUID id) {
        Brand brand = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la marca solicitada"));
        return mapper.toResponse(brand);
    }

    @Override
    public List<BrandResponseDTO> getAll(SearchBrandRequestDTO requestDTO) {
        Specification<Brand> specs = Specification.allOf(
                BrandSpecs.nameContains(requestDTO.name()));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public BrandResponseDTO create(CreateBrandRequestDTO requestDTO) {
        if (repository.existsByName(requestDTO.name())) {
            throw new EntityAlreadyExistsException(
                "Ya existe una marca con el mismo nombre");
        }
        Brand brand = mapper.toBrand(requestDTO);
        String logoKey = s3Service.uploadImage(requestDTO.logo());
        brand.setLogoKey(logoKey);
        brand = repository.save(brand);
        return mapper.toResponse(brand);
    }

    @Override
    public BrandResponseDTO update(UUID id, UpdateBrandRequestDTO requestDTO) {
        Brand brand = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe la marca solicitada"));
        String newName = requestDTO.name();
        if (newName != null && !newName.equals(brand.getName())) {
            if (repository.existsByNameAndIdNot(newName, id)) {
                throw new EntityAlreadyExistsException(
                    "Ya existe una marca con el mismo nombre");
            }
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
    
}

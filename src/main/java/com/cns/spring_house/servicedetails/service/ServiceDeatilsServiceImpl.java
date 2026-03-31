package com.cns.spring_house.servicedetails.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.servicedetails.dto.CreateServiceDetailsRequestDTO;
import com.cns.spring_house.servicedetails.dto.ServiceDetailsResponseDTO;
import com.cns.spring_house.servicedetails.dto.UpdateServiceDetailsRequestDTO;
import com.cns.spring_house.servicedetails.entity.ServiceDetails;
import com.cns.spring_house.servicedetails.mapper.ServiceDetailsMapper;
import com.cns.spring_house.servicedetails.repository.ServiceDetailsRepository;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Transactional
@Service
public class ServiceDeatilsServiceImpl implements ServiceDetailsService {
    
    private final ServiceDetailsRepository repository;
    private final ServiceDetailsMapper mapper;
    private final WorkshopRepository workshopRepository;
    private final ProductRepository productRepository;

    @Override
    public List<ServiceDetailsResponseDTO> getAll() {
        List<ServiceDetailsResponseDTO> services = repository.findAll()
                .stream()
                .map(mapper::toResponse)
                .toList();
        return services;
    }

    @Override
    public ServiceDetailsResponseDTO get(UUID id) {
        ServiceDetails service = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el servicio especificado"));
        return mapper.toResponse(service);
    }

    @Override
    public ServiceDetailsResponseDTO create(CreateServiceDetailsRequestDTO requestDTO) {
        Workshop workshop = workshopRepository
                .findById(SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
        Product product = productRepository
                .findById(requestDTO.product())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el servicio especificado"));
        if (product.getFamily().getCategory() != FamilyCategory.SERVICE) {
            throw new ValidationException("El producto no es un servicio");
        }
        boolean exists = repository.existsByWorkshopIdAndProductId(
                workshop.getId(), product.getId());
        if (exists) {
            throw new EntityAlreadyExistsException(
                    "Ya existen detalles para este servicio en este taller");
        }
        ServiceDetails service = mapper.toServiceDetails(requestDTO);
        service.setWorkshop(workshop);
        service.setProduct(product);
        service = repository.save(service);
        return mapper.toResponse(service);
    }

    @Override
    public ServiceDetailsResponseDTO update(UUID id, UpdateServiceDetailsRequestDTO requestDTO) {
        ServiceDetails service = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el servicio especificado"));
        mapper.updateServiceFromDTO(requestDTO, service);
        service = repository.save(service);
        return mapper.toResponse(service);
    }

}

package com.cns.spring_house.inventory.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.inventory.dto.CreateInventoryRequestDTO;
import com.cns.spring_house.inventory.dto.SearchInventoryRequestDTO;
import com.cns.spring_house.inventory.dto.InventoryResponseDTO;
import com.cns.spring_house.inventory.dto.UpdateInventoryRequestDTO;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.inventory.mapper.InventoryMapper;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.inventory.repository.InventorySpecs;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository repository;
    private final WorkshopRepository workshopRespository;
    private final ProductRepository productRepository;
    private final InventoryMapper mapper;

    @Override
    public InventoryResponseDTO get(UUID id) {
        Inventory inventory = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el registro solicitado"));
        return mapper.toResponse(inventory);
    }

    @Override
    public List<InventoryResponseDTO> getAll(SearchInventoryRequestDTO requestDTO) {
        Specification<Inventory> specs = Specification.allOf(
                InventorySpecs.hasProduct(requestDTO.product()),
                InventorySpecs.hasWorkshop(requestDTO.workshop()));
        return repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public InventoryResponseDTO create(CreateInventoryRequestDTO requestDTO) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        boolean alreadyExists = repository.existsByProductIdAndWorkshopId(
                requestDTO.product(), workshopId);
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un registro para ese producto en ese taller");
        }
        Workshop workshop = workshopRespository
                .findById(workshopId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
        Product product = productRepository
                .findById(requestDTO.product())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el producto especificado"));
        Inventory inventory = mapper.toInventory(requestDTO);
        inventory.setWorkshop(workshop);
        inventory.setProduct(product);
        inventory = repository.save(inventory);
        return mapper.toResponse(inventory);
    }

    @Override
    public InventoryResponseDTO update(UUID id, UpdateInventoryRequestDTO requestDTO) {
        Inventory inventory = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el registro especificado"));
        mapper.updateInventoryFromDTO(requestDTO, inventory);
        inventory = repository.save(inventory);
        return mapper.toResponse(inventory);
    }

}

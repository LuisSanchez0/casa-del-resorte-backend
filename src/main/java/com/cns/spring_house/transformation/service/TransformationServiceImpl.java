package com.cns.spring_house.transformation.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.TransformationResponseDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.handler.TransformationHandlerRegistry;
import com.cns.spring_house.transformation.handler.TransformationHandler;
import com.cns.spring_house.transformation.mapper.TransformationMapper;
import com.cns.spring_house.transformation.repository.TransformationRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class TransformationServiceImpl implements TransformationService {
    
    private final TransformationMapper mapper;
    private final TransformationRepository repository;
    private final InventoryItemRepository itemRepository;
    private final WorkshopRepository workshopRepository;
    private final EmployeeRepository employeeRepository;
    private final TransformationHandlerRegistry registry;

    @Override
    public TransformationResponseDTO create(
            CreateTransformationRequestDTO requestDTO) {
        InventoryItem inputItem = itemRepository
                .findById(requestDTO.inputItem())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el item especificado"));
        if (inputItem.getStatus() != InventoryItemStatus.AVAILABLE) {
            throw new ValidationException(
                    "El item no esta disponible");
        }
        TransformationHandler handler = getHandler(inputItem);
        Transformation transformation = startTransformation();
        handler.generate(
                startTransformation(),
                inputItem,
                requestDTO.outputProduct());
        transformation = repository.save(transformation);
        return mapper.toResponse(transformation);
    }

    private TransformationHandler getHandler(InventoryItem inputItem) {
        FamilyCategory category = inputItem.getInventory().getProduct()
                .getFamily().getCategory();
        TransformationHandler handler = registry.get(category);
        return handler;
    }

    private Transformation startTransformation() {
        Employee employee = employeeRepository
                .findById(SecurityUtils.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "El empleado especificado no existe"));
        Workshop workshop = workshopRepository
                .findById(SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "El taller especificado no existe"));
        Transformation transformation = Transformation.builder()
                .employee(employee)
                .workshop(workshop)
                .performedDate(LocalDateTime.now())
                .build();
        return transformation;
    }

}

package com.cns.spring_house.employee.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.employee.dto.CreateEmployeeRequestDTO;
import com.cns.spring_house.employee.dto.EmployeeResponseDTO;
import com.cns.spring_house.employee.dto.SearchEmployeeRequestDTO;
import com.cns.spring_house.employee.dto.UpdateEmployeeRequestDTO;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.employee.mapper.EmployeeMapper;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.employee.repository.EmployeeSpecs;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final WorkshopRepository workshopRespository;
    private final EmployeeMapper mapper;

    @Override
    public List<EmployeeResponseDTO> getAll(SearchEmployeeRequestDTO requestDTO) {
        Specification<Employee> specs = Specification.allOf(
                Specification.anyOf(
                        EmployeeSpecs.firstNameContains(requestDTO.q()),
                        EmployeeSpecs.lastNameContains(requestDTO.q()),
                        EmployeeSpecs.dpiContains(requestDTO.q()),
                        EmployeeSpecs.phoneNumberContains(requestDTO.q())),
                EmployeeSpecs.hasWorkshop(requestDTO.workshop()));
        return employeeRepository
                .findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public EmployeeResponseDTO get(UUID id) {
        Employee employee = employeeRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el empleado solicitado"));
        return mapper.toResponse(employee);
    }

    @Override
    public EmployeeResponseDTO create(CreateEmployeeRequestDTO requestDTO) {
        if (employeeRepository.existsByDpi(requestDTO.dpi())) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un empleado con el mismo dpi");
        }
        Workshop workshop = workshopRespository
                .findById(requestDTO.workshop())
                .orElseThrow(() -> new EntityNotFoundException("No existe el taller especificado"));
        Employee employee = mapper.toEmployee(requestDTO);
        employee.setWorkshop(workshop);
        employee = employeeRepository.save(employee);
        return mapper.toResponse(employee);
    }

    @Override
    public EmployeeResponseDTO update(UUID id, UpdateEmployeeRequestDTO requestDTO) {
        Employee employee = employeeRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el empleado especificado"));
        if (employeeRepository.existsByDpiAndIdNot(requestDTO.dpi(), id)) {
            throw new EntityAlreadyExistsException(
                    "Ya existe otro empleado con el mismo dpi");
        }
        mapper.updateWorkshopFromDTO(requestDTO, employee);
        if (requestDTO.workshop() != null) {
            Workshop workshop = workshopRespository
                    .findById(requestDTO.workshop())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No existe el taller especificado"));
            employee.setWorkshop(workshop);
        }
        employee = employeeRepository.save(employee);
        return mapper.toResponse(employee);
    }

}

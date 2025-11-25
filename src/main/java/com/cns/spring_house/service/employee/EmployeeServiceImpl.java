package com.cns.spring_house.service.employee;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.cns.spring_house.dto.employee.CreateEmployeeRequestDTO;
import com.cns.spring_house.dto.employee.EmployeeResponseDTO;
import com.cns.spring_house.dto.employee.SearchEmployeeRequestDTO;
import com.cns.spring_house.dto.employee.UpdateEmployeeRequestDTO;
import com.cns.spring_house.exception.EntityAlreadyExistsException;
import com.cns.spring_house.exception.EntityNotFoundException;
import com.cns.spring_house.mapper.employee.EmployeeMapper;
import com.cns.spring_house.model.employee.Employee;
import com.cns.spring_house.model.workshop.Workshop;
import com.cns.spring_house.repository.employee.EmployeeRepository;
import com.cns.spring_house.repository.employee.EmployeeSpecs;
import com.cns.spring_house.repository.workshop.WorkshopRespository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final WorkshopRespository workshopRespository;
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
                    .orElseThrow(() -> new EntityNotFoundException("No existe el taller especificado"));
            employee.setWorkshop(workshop);
        }
        employee = employeeRepository.save(employee);
        return mapper.toResponse(employee);
    }

}

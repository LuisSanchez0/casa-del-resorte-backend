package com.cns.spring_house.service.employee;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.employee.CreateEmployeeRequestDTO;
import com.cns.spring_house.dto.employee.EmployeeResponseDTO;
import com.cns.spring_house.dto.employee.SearchEmployeeRequestDTO;
import com.cns.spring_house.dto.employee.UpdateEmployeeRequestDTO;

public interface EmployeeService {

    List<EmployeeResponseDTO> getAll(SearchEmployeeRequestDTO requestDTO);

    EmployeeResponseDTO get(UUID id);

    EmployeeResponseDTO create(CreateEmployeeRequestDTO requestDTO);

    EmployeeResponseDTO update(UUID id, UpdateEmployeeRequestDTO requestDTO);

}

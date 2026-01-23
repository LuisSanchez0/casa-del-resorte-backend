package com.cns.spring_house.employee.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.employee.dto.CreateEmployeeRequestDTO;
import com.cns.spring_house.employee.dto.EmployeeResponseDTO;
import com.cns.spring_house.employee.dto.SearchEmployeeRequestDTO;
import com.cns.spring_house.employee.dto.UpdateEmployeeRequestDTO;

public interface EmployeeService {

    List<EmployeeResponseDTO> getAll(SearchEmployeeRequestDTO requestDTO);

    EmployeeResponseDTO get(UUID id);

    EmployeeResponseDTO create(CreateEmployeeRequestDTO requestDTO);

    EmployeeResponseDTO update(UUID id, UpdateEmployeeRequestDTO requestDTO);

}

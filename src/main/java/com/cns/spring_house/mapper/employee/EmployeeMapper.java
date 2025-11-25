package com.cns.spring_house.mapper.employee;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.dto.employee.CreateEmployeeRequestDTO;
import com.cns.spring_house.dto.employee.EmployeeResponseDTO;
import com.cns.spring_house.dto.employee.UpdateEmployeeRequestDTO;
import com.cns.spring_house.mapper.workshop.WorkshopMapper;
import com.cns.spring_house.model.employee.Employee;

@Mapper(componentModel = "spring", uses = { WorkshopMapper.class })
public interface EmployeeMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Employee toEmployee(CreateEmployeeRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateWorkshopFromDTO(UpdateEmployeeRequestDTO requestDTO, @MappingTarget Employee employee);

    EmployeeResponseDTO toResponse(Employee employee);

}

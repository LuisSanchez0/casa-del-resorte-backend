package com.cns.spring_house.employee.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.employee.dto.CreateEmployeeRequestDTO;
import com.cns.spring_house.employee.dto.EmployeeResponseDTO;
import com.cns.spring_house.employee.dto.UpdateEmployeeRequestDTO;
import com.cns.spring_house.workshop.mapper.WorkshopMapper;
import com.cns.spring_house.employee.entity.Employee;

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

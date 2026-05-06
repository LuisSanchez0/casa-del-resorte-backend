package com.cns.spring_house.job.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;

import com.cns.spring_house.job.dto.CreateJobRequestDTO;
import com.cns.spring_house.job.dto.JobResponseDTO;
import com.cns.spring_house.job.dto.UpdateJobRequestDTO;
import com.cns.spring_house.job.entity.Job;

import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = ComponentModel.SPRING)
public interface JobMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Job toJob(CreateJobRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateServiceFromDTO(UpdateJobRequestDTO requestDTO, @MappingTarget Job service);

    JobResponseDTO toResponse(Job job);

}

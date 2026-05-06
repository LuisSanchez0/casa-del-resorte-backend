package com.cns.spring_house.job.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants.ComponentModel;

import com.cns.spring_house.employee.mapper.EmployeeMapper;
import com.cns.spring_house.job.dto.JobLogResponseDTO;
import com.cns.spring_house.job.entity.JobLog;

@Mapper(componentModel = ComponentModel.SPRING, uses = { EmployeeMapper.class, JobMapper.class })
public interface JobLogMapper {

    JobLogResponseDTO toResponse(JobLog log);
    
}

package com.cns.spring_house.dailylog.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants.ComponentModel;

import com.cns.spring_house.dailylog.dto.DailyLogResponseDTO;
import com.cns.spring_house.dailylog.entity.DailyLog;

@Mapper(componentModel = ComponentModel.SPRING)
public interface DailyLogMapper {
    
    DailyLogResponseDTO toResponse(DailyLog dailyLog);

}

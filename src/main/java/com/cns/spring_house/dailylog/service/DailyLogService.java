package com.cns.spring_house.dailylog.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dailylog.dto.CreateDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.DailyLogResponseDTO;
import com.cns.spring_house.dailylog.dto.SearchDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.UpdateDailyLogRequestDTO;

public interface DailyLogService {
    
    List<DailyLogResponseDTO> getAll(SearchDailyLogRequestDTO requestDTO);
    DailyLogResponseDTO get(UUID id);
    DailyLogResponseDTO create(CreateDailyLogRequestDTO requestDTO);
    DailyLogResponseDTO update(UUID id, UpdateDailyLogRequestDTO requestDTO);

}

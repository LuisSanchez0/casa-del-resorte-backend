package com.cns.spring_house.job.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.job.dto.CreateJobLogRequestDTO;
import com.cns.spring_house.job.dto.CreateJobRequestDTO;
import com.cns.spring_house.job.dto.JobLogResponseDTO;
import com.cns.spring_house.job.dto.JobResponseDTO;
import com.cns.spring_house.job.dto.SearchJobLogRequestDTO;
import com.cns.spring_house.job.dto.SearchJobRequestDTO;
import com.cns.spring_house.job.dto.UpdateJobRequestDTO;

public interface JobService {
    
    List<JobResponseDTO> getAll(SearchJobRequestDTO requestDTO);
    JobResponseDTO get(UUID id);
    JobResponseDTO create(CreateJobRequestDTO requestDTO);
    JobResponseDTO update(UUID id, UpdateJobRequestDTO requestDTO);
    List<JobLogResponseDTO> getAllLogs(SearchJobLogRequestDTO requestDTO);
    JobLogResponseDTO getLog(UUID id);
    JobLogResponseDTO createLog(CreateJobLogRequestDTO requestDTO);

}

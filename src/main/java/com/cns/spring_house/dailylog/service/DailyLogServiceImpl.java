package com.cns.spring_house.dailylog.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.dailylog.dto.CreateDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.DailyLogResponseDTO;
import com.cns.spring_house.dailylog.dto.SearchDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.UpdateDailyLogRequestDTO;
import com.cns.spring_house.dailylog.entity.DailyLog;
import com.cns.spring_house.dailylog.mapper.DailyLogMapper;
import com.cns.spring_house.dailylog.repository.DailyLogRepository;
import com.cns.spring_house.dailylog.repository.DailyLogSpecs;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class DailyLogServiceImpl implements DailyLogService {
    
    private final DailyLogRepository repository;
    private final DailyLogMapper mapper;
    private final EmployeeRepository employeeRepository;
    
    @Override
    public List<DailyLogResponseDTO> getAll(SearchDailyLogRequestDTO requestDTO) {
        Specification<DailyLog> specs = Specification.allOf(
                DailyLogSpecs.hasEmployee(requestDTO.employee()),
                DailyLogSpecs.hasWorkshop(requestDTO.workshop()),
                DailyLogSpecs.performedDateBetween(requestDTO.minDate(), requestDTO.maxDate()));
        List<DailyLogResponseDTO> dailyLogs = repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
        return dailyLogs;
    }

    @Override
    public DailyLogResponseDTO get(UUID id) {
        DailyLog dailyLog = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el log solicitado"));
        return mapper.toResponse(dailyLog);
    }

    @Override
    public DailyLogResponseDTO create(CreateDailyLogRequestDTO requestDTO) {
        Employee employee = employeeRepository.findById(SecurityUtils.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el empleado especificado"));
        DailyLog dailyLog = DailyLog.builder()
                .employee(employee)
                .workshop(employee.getWorkshop())
                .description(requestDTO.description())
                .performedDate(LocalDate.now())
                .build();
        dailyLog = repository.save(dailyLog);
        return mapper.toResponse(dailyLog);
    }

    @Override
    public DailyLogResponseDTO update(UUID id, UpdateDailyLogRequestDTO requestDTO) {
        DailyLog dailyLog = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el log solicitado"));
        dailyLog.setDescription(requestDTO.description());
        dailyLog = repository.save(dailyLog);
        return mapper.toResponse(dailyLog);
    }

}

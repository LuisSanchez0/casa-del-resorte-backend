package com.cns.spring_house.job.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.job.dto.CreateJobLogRequestDTO;
import com.cns.spring_house.job.dto.CreateJobRequestDTO;
import com.cns.spring_house.job.dto.JobLogResponseDTO;
import com.cns.spring_house.job.dto.JobResponseDTO;
import com.cns.spring_house.job.dto.SearchJobLogRequestDTO;
import com.cns.spring_house.job.dto.SearchJobRequestDTO;
import com.cns.spring_house.job.dto.UpdateJobRequestDTO;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.job.mapper.JobLogMapper;
import com.cns.spring_house.job.mapper.JobMapper;
import com.cns.spring_house.job.repository.JobLogRepository;
import com.cns.spring_house.job.repository.JobLogSpecs;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.job.repository.JobSpecs;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.workresult.service.WorkResultService;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Transactional
@Service
public class JobServiceImpl implements JobService {
    
    private final JobRepository repository;
    private final JobLogRepository logRepository;
    private final JobMapper mapper;
    private final JobLogMapper logMapper;
    private final WorkshopRepository workshopRepository;
    private final ProductRepository productRepository;
    private final CurrentUserService currentUserService;
    private final WorkResultService workResultService;

    @Override
    public List<JobResponseDTO> getAll(SearchJobRequestDTO requestDTO) {
        Specification<Job> specs = Specification.allOf(
                JobSpecs.nameContains(requestDTO.name()),
                JobSpecs.priceBetween(requestDTO.minPrice(), requestDTO.maxPrice()),
                JobSpecs.hasWorkshop(SecurityUtils.getWorkshopId()));
        List<JobResponseDTO> services = repository.findAll(specs)
                .stream()
                .map(mapper::toResponse)
                .toList();
        return services;
    }

    @Override
    public JobResponseDTO get(UUID id) {
        Job service = repository
                .findByIdAndWorkshopId(id, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el servicio especificado"));
        return mapper.toResponse(service);
    }

    @Override
    public JobResponseDTO create(CreateJobRequestDTO requestDTO) {
        Workshop workshop = workshopRepository
                .findById(SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
        Product product = productRepository
                .findById(requestDTO.product())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el servicio especificado"));
        if (product.getFamily().getCategory() != FamilyCategory.JOB) {
            throw new ValidationException("El producto no es un servicio");
        }
        boolean exists = repository.existsByWorkshopIdAndProductId(
                workshop.getId(), product.getId());
        if (exists) {
            throw new EntityAlreadyExistsException(
                    "Ya existen detalles para este servicio en este taller");
        }
        Job service = mapper.toJob(requestDTO);
        service.setWorkshop(workshop);
        service.setProduct(product);
        service = repository.save(service);
        return mapper.toResponse(service);
    }

    @Override
    public JobResponseDTO update(UUID id, UpdateJobRequestDTO requestDTO) {
        Job service = repository
                .findByIdAndWorkshopId(id, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el servicio especificado"));
        mapper.updateServiceFromDTO(requestDTO, service);
        service = repository.save(service);
        return mapper.toResponse(service);
    }

    @Override
    public List<JobLogResponseDTO> getAllLogs(SearchJobLogRequestDTO requestDTO) {
        Specification<JobLog> specs = Specification.allOf(
                JobLogSpecs.hasEmployee(requestDTO.employee()),
                JobLogSpecs.hasWorkshop(SecurityUtils.getWorkshopId()),
                JobLogSpecs.hasJob(requestDTO.job()),
                JobLogSpecs.performedDateBetween(requestDTO.minDate(), requestDTO.maxDate()));
        List<JobLogResponseDTO> logs = logRepository.findAll(specs)
                .stream()
                .map(logMapper::toResponse)
                .toList();
        return logs;
    }

    @Override
    public JobLogResponseDTO getLog(UUID id) {
        JobLog log = logRepository
                .findByIdAndWorkshopId(id, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el log solicitado"));
        return logMapper.toResponse(log);
    }

    @Override
    public JobLogResponseDTO createLog(CreateJobLogRequestDTO requestDTO) {
        Job job = repository
                .findByIdAndWorkshopId(
                        requestDTO.job(), SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el servicio especificado"));
        // SecurityUtils.getId() es UserAccount.id, no Employee.id.
        Employee employee = currentUserService.getCurrentEmployee();
        if (!employee.getWorkshop().getId().equals(job.getWorkshop().getId())) {
            throw new ValidationException(
                    "El taller no corresponde al del servicio");
        }
        JobLog log = JobLog.builder()
                .performedDate(LocalDateTime.now())
                .job(job)
                .employee(employee)
                .build();
        log = logRepository.save(log);
        workResultService.createPendingFromJobLog(log);
        return logMapper.toResponse(log);
    }

}

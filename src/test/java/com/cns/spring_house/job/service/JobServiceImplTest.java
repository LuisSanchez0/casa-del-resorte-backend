package com.cns.spring_house.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.job.dto.CreateJobLogRequestDTO;
import com.cns.spring_house.job.dto.SearchJobLogRequestDTO;
import com.cns.spring_house.job.dto.SearchJobRequestDTO;
import com.cns.spring_house.job.dto.UpdateJobRequestDTO;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.job.mapper.JobLogMapper;
import com.cns.spring_house.job.mapper.JobMapper;
import com.cns.spring_house.job.repository.JobLogRepository;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.workresult.service.WorkResultService;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@ExtendWith(MockitoExtension.class)
class JobServiceImplTest {

    @Mock
    private JobRepository repository;

    @Mock
    private JobLogRepository logRepository;

    @Mock
    private JobMapper mapper;

    @Mock
    private JobLogMapper logMapper;

    @Mock
    private WorkshopRepository workshopRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private WorkResultService workResultService;

    @InjectMocks
    private JobServiceImpl service;

    private final UUID workshopId = UUID.randomUUID();
    /** UserAccount.id: lo que realmente viaja como subject del JWT. */
    private final UUID userAccountId = UUID.randomUUID();
    /** Employee.id: SIEMPRE distinto del anterior en producción. */
    private final UUID employeeId = UUID.randomUUID();
    private final UUID foreignWorkshopId = UUID.randomUUID();
    private final Workshop workshop = Workshop.builder()
            .id(workshopId)
            .build();

    @BeforeEach
    void setUpAuthentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(userAccountId.toString())
                .claim("workshop", workshopId.toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private Job job() {
        return Job.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .product(Product.builder().id(UUID.randomUUID()).build())
                .build();
    }

    private Employee employee() {
        return Employee.builder()
                .id(employeeId)
                .workshop(workshop)
                .build();
    }

    private void stubLogSaveAssigningId() {
        when(logRepository.save(any(JobLog.class))).thenAnswer(invocation -> {
            JobLog log = invocation.getArgument(0);
            log.setId(UUID.randomUUID());
            return log;
        });
    }

    @Test
    void createLogCreatesPendingWorkResultFromThePersistedLog() {
        Job job = job();
        when(repository.findByIdAndWorkshopId(job.getId(), workshopId))
                .thenReturn(Optional.of(job));
        when(currentUserService.getCurrentEmployee()).thenReturn(employee());
        stubLogSaveAssigningId();

        service.createLog(new CreateJobLogRequestDTO(job.getId()));

        ArgumentCaptor<JobLog> captor = ArgumentCaptor.forClass(JobLog.class);
        verify(workResultService).createPendingFromJobLog(captor.capture());
        JobLog registered = captor.getValue();
        assertThat(registered.getId()).isNotNull();
        assertThat(registered.getJob()).isEqualTo(job);
    }

    @Test
    void twoConsecutiveLogsOfTheSameJobRegisterTwoDifferentWorkResults() {
        Job job = job();
        when(repository.findByIdAndWorkshopId(job.getId(), workshopId))
                .thenReturn(Optional.of(job));
        when(currentUserService.getCurrentEmployee()).thenReturn(employee());
        stubLogSaveAssigningId();

        service.createLog(new CreateJobLogRequestDTO(job.getId()));
        service.createLog(new CreateJobLogRequestDTO(job.getId()));

        ArgumentCaptor<JobLog> captor = ArgumentCaptor.forClass(JobLog.class);
        verify(workResultService, times(2))
                .createPendingFromJobLog(captor.capture());
        List<JobLog> logs = captor.getAllValues();
        assertThat(logs.get(0).getId()).isNotEqualTo(logs.get(1).getId());
        assertThat(logs.get(0).getJob()).isEqualTo(logs.get(1).getJob());
    }

    @Test
    void createLogRejectsJobFromAnotherWorkshop() {
        UUID foreignJobId = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(foreignJobId, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createLog(
                new CreateJobLogRequestDTO(foreignJobId)))
                .isInstanceOf(EntityNotFoundException.class);

        verify(logRepository, never()).save(any());
        verifyNoInteractions(workResultService);
    }

    @Test
    void getUsesWorkshopScopedLookup() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(id, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(EntityNotFoundException.class);

        verify(repository, never()).findById(any());
    }

    @Test
    void updateUsesWorkshopScopedLookup() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(id, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                id, new UpdateJobRequestDTO(null)))
                .isInstanceOf(EntityNotFoundException.class);

        verify(repository, never()).findById(any());
    }

    @Test
    void getLogUsesWorkshopScopedLookup() {
        UUID id = UUID.randomUUID();
        when(logRepository.findByIdAndWorkshopId(id, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLog(id))
                .isInstanceOf(EntityNotFoundException.class);

        verify(logRepository, never()).findById(any());
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void getAllIgnoresTheWorkshopSentByTheClient() {
        when(repository.findAll(any(Specification.class)))
                .thenReturn(List.of());

        service.getAll(new SearchJobRequestDTO(
                null, null, foreignWorkshopId, null));

        ArgumentCaptor<Specification> captor =
                ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll(captor.capture());

        Root<Job> root = mock(Root.class);
        Path workshopPath = mock(Path.class);
        Path idPath = mock(Path.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        doReturn(workshopPath).when(root).get("workshop");
        doReturn(idPath).when(workshopPath).get("id");

        captor.getValue().toPredicate(root, mock(CriteriaQuery.class), builder);

        verify(builder).equal(idPath, workshopId);
        verify(builder, never()).equal(idPath, foreignWorkshopId);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void getAllLogsIgnoresTheWorkshopSentByTheClient() {
        when(logRepository.findAll(any(Specification.class)))
                .thenReturn(List.of());

        service.getAllLogs(new SearchJobLogRequestDTO(
                foreignWorkshopId, null, null, null, null));

        ArgumentCaptor<Specification> captor =
                ArgumentCaptor.forClass(Specification.class);
        verify(logRepository).findAll(captor.capture());

        Root<JobLog> root = mock(Root.class);
        Path jobPath = mock(Path.class);
        Path workshopPath = mock(Path.class);
        Path idPath = mock(Path.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        doReturn(jobPath).when(root).get("job");
        doReturn(workshopPath).when(jobPath).get("workshop");
        doReturn(idPath).when(workshopPath).get("id");

        captor.getValue().toPredicate(root, mock(CriteriaQuery.class), builder);

        verify(builder).equal(idPath, workshopId);
        verify(builder, never()).equal(idPath, foreignWorkshopId);
    }

}

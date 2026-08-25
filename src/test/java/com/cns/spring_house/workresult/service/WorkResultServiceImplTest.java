package com.cns.spring_house.workresult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.workresult.dto.SearchWorkResultRequestDTO;
import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workresult.entity.WorkResultStatus;
import com.cns.spring_house.workresult.mapper.WorkResultMapper;
import com.cns.spring_house.workresult.repository.WorkResultRepository;
import com.cns.spring_house.workshop.entity.Workshop;

@ExtendWith(MockitoExtension.class)
class WorkResultServiceImplTest {

    @Mock
    private WorkResultRepository repository;

    @Mock
    private WorkResultMapper mapper;

    @InjectMocks
    private WorkResultServiceImpl service;

    private final UUID workshopId = UUID.randomUUID();
    private final Workshop workshop = Workshop.builder()
            .id(workshopId)
            .build();
    private final Workshop otherWorkshop = Workshop.builder()
            .id(UUID.randomUUID())
            .build();

    @BeforeEach
    void setUpAuthentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("workshop", workshopId.toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private Job job(Workshop jobWorkshop, Product product) {
        return Job.builder()
                .id(UUID.randomUUID())
                .workshop(jobWorkshop)
                .product(product)
                .build();
    }

    private JobLog jobLog(Job job) {
        return JobLog.builder()
                .id(UUID.randomUUID())
                .job(job)
                .build();
    }

    private Product product() {
        return Product.builder()
                .id(UUID.randomUUID())
                .name("CAMBIO DE RESORTE")
                .build();
    }

    private InventoryItem item(Workshop itemWorkshop) {
        return InventoryItem.builder()
                .id(UUID.randomUUID())
                .inventory(Inventory.builder()
                        .workshop(itemWorkshop)
                        .product(product())
                        .build())
                .build();
    }

    @Test
    void createPendingFromJobLogCreatesPendingRecordReferencingTheJobLog() {
        JobLog log = jobLog(job(workshop, product()));
        when(repository.existsByJobLogId(log.getId())).thenReturn(false);

        service.createPendingFromJobLog(log);

        ArgumentCaptor<WorkResult> captor =
                ArgumentCaptor.forClass(WorkResult.class);
        verify(repository).save(captor.capture());
        WorkResult saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(WorkResultStatus.PENDING);
        assertThat(saved.getJobLog()).isEqualTo(log);
        assertThat(saved.getWorkshop()).isEqualTo(workshop);
        assertThat(saved.getItem()).isNull();
    }

    @Test
    void twoJobLogsOfTheSameJobCreateTwoDifferentWorkResults() {
        Job sharedJob = job(workshop, product());
        JobLog first = jobLog(sharedJob);
        JobLog second = jobLog(sharedJob);
        when(repository.existsByJobLogId(any())).thenReturn(false);

        service.createPendingFromJobLog(first);
        service.createPendingFromJobLog(second);

        ArgumentCaptor<WorkResult> captor =
                ArgumentCaptor.forClass(WorkResult.class);
        verify(repository, org.mockito.Mockito.times(2))
                .save(captor.capture());
        List<WorkResult> saved = captor.getAllValues();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getJobLog()).isEqualTo(first);
        assertThat(saved.get(1).getJobLog()).isEqualTo(second);
        assertThat(saved.get(0).getJobLog().getJob())
                .isEqualTo(saved.get(1).getJobLog().getJob());
    }

    @Test
    void createPendingFromJobLogRejectsJobFromAnotherWorkshop() {
        JobLog log = jobLog(job(otherWorkshop, product()));

        assertThatThrownBy(() -> service.createPendingFromJobLog(log))
                .isInstanceOf(ValidationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPendingFromJobLogRejectsDuplicateForTheSameJobLog() {
        JobLog log = jobLog(job(workshop, product()));
        when(repository.existsByJobLogId(log.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.createPendingFromJobLog(log))
                .isInstanceOf(EntityAlreadyExistsException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPendingFromItemCreatesPendingRecord() {
        InventoryItem inventoryItem = item(workshop);
        when(repository.existsByItemId(inventoryItem.getId()))
                .thenReturn(false);

        service.createPendingFromItem(inventoryItem);

        ArgumentCaptor<WorkResult> captor =
                ArgumentCaptor.forClass(WorkResult.class);
        verify(repository).save(captor.capture());
        WorkResult saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(WorkResultStatus.PENDING);
        assertThat(saved.getItem()).isEqualTo(inventoryItem);
        assertThat(saved.getWorkshop()).isEqualTo(workshop);
        assertThat(saved.getJobLog()).isNull();
    }

    @Test
    void createPendingFromItemRejectsDuplicateEvenWhenPreviousOneIsSold() {
        InventoryItem inventoryItem = item(workshop);
        when(repository.existsByItemId(inventoryItem.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createPendingFromItem(inventoryItem))
                .isInstanceOf(EntityAlreadyExistsException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPendingFromItemRejectsItemFromAnotherWorkshop() {
        InventoryItem inventoryItem = item(otherWorkshop);

        assertThatThrownBy(() -> service.createPendingFromItem(inventoryItem))
                .isInstanceOf(ValidationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void resolvePendingReturnsRecordWhenProductMatchesTheJobLog() {
        Product jobProduct = product();
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .jobLog(jobLog(job(workshop, jobProduct)))
                .build();
        when(repository.findByIdAndWorkshopIdAndStatus(
                workResult.getId(), workshopId, WorkResultStatus.PENDING))
                .thenReturn(Optional.of(workResult));

        WorkResult resolved = service.resolvePending(
                workResult.getId(), jobProduct.getId(), null);

        assertThat(resolved).isEqualTo(workResult);
    }

    @Test
    void resolvePendingRejectsJobLogRecordWhenProductDoesNotMatch() {
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .jobLog(jobLog(job(workshop, product())))
                .build();
        when(repository.findByIdAndWorkshopIdAndStatus(
                workResult.getId(), workshopId, WorkResultStatus.PENDING))
                .thenReturn(Optional.of(workResult));

        assertThatThrownBy(() -> service.resolvePending(
                workResult.getId(), UUID.randomUUID(), null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void resolvePendingRejectsJobLogRecordSentForAnItemDetail() {
        Product jobProduct = product();
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .jobLog(jobLog(job(workshop, jobProduct)))
                .build();
        when(repository.findByIdAndWorkshopIdAndStatus(
                workResult.getId(), workshopId, WorkResultStatus.PENDING))
                .thenReturn(Optional.of(workResult));

        assertThatThrownBy(() -> service.resolvePending(
                workResult.getId(), jobProduct.getId(), UUID.randomUUID()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void resolvePendingReturnsRecordWhenItemMatches() {
        InventoryItem inventoryItem = item(workshop);
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .item(inventoryItem)
                .build();
        when(repository.findByIdAndWorkshopIdAndStatus(
                workResult.getId(), workshopId, WorkResultStatus.PENDING))
                .thenReturn(Optional.of(workResult));

        WorkResult resolved = service.resolvePending(
                workResult.getId(),
                inventoryItem.getInventory().getProduct().getId(),
                inventoryItem.getId());

        assertThat(resolved).isEqualTo(workResult);
    }

    @Test
    void resolvePendingRejectsItemRecordWhenItemDoesNotMatch() {
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .item(item(workshop))
                .build();
        when(repository.findByIdAndWorkshopIdAndStatus(
                workResult.getId(), workshopId, WorkResultStatus.PENDING))
                .thenReturn(Optional.of(workResult));

        assertThatThrownBy(() -> service.resolvePending(
                workResult.getId(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void resolvePendingRejectsItemRecordSentForAJobDetail() {
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .item(item(workshop))
                .build();
        when(repository.findByIdAndWorkshopIdAndStatus(
                workResult.getId(), workshopId, WorkResultStatus.PENDING))
                .thenReturn(Optional.of(workResult));

        assertThatThrownBy(() -> service.resolvePending(
                workResult.getId(), UUID.randomUUID(), null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void resolvePendingRejectsRecordFromAnotherWorkshopOrAlreadySold() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndWorkshopIdAndStatus(
                id, workshopId, WorkResultStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolvePending(
                id, UUID.randomUUID(), null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void markAsSoldChangesStatusOfTheSelectedRecord() {
        Product jobProduct = product();
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .jobLog(jobLog(job(workshop, jobProduct)))
                .build();
        when(repository.findForUpdate(
                workResult.getId(), workshopId, WorkResultStatus.PENDING))
                .thenReturn(Optional.of(workResult));

        service.markAsSold(workResult.getId(), jobProduct.getId(), null);

        assertThat(workResult.getStatus()).isEqualTo(WorkResultStatus.SOLD);
    }

    @Test
    void markAsSoldRejectsRecordThatIsNoLongerPending() {
        UUID id = UUID.randomUUID();
        when(repository.findForUpdate(
                id, workshopId, WorkResultStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAsSold(
                id, UUID.randomUUID(), null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void getAllPendingReturnsOnlyAvailableRecordsOfTheAuthenticatedWorkshop() {
        when(repository.findAvailableByWorkshopIdAndStatus(
                workshopId, WorkResultStatus.PENDING))
                .thenReturn(List.of());

        service.getAll(new SearchWorkResultRequestDTO(
                WorkResultStatus.PENDING));

        verify(repository).findAvailableByWorkshopIdAndStatus(
                workshopId, WorkResultStatus.PENDING);
        verify(repository, never()).findAllByWorkshopIdAndStatus(
                any(), any());
    }

    @Test
    void getAllSoldKeepsTheHistoryOfTheAuthenticatedWorkshop() {
        when(repository.findAllByWorkshopIdAndStatus(
                workshopId, WorkResultStatus.SOLD))
                .thenReturn(List.of());

        service.getAll(new SearchWorkResultRequestDTO(
                WorkResultStatus.SOLD));

        verify(repository).findAllByWorkshopIdAndStatus(
                workshopId, WorkResultStatus.SOLD);
        verify(repository, never()).findAvailableByWorkshopIdAndStatus(
                any(), any());
    }

    @Test
    void getAllWithoutStatusQueriesOnlyAuthenticatedWorkshop() {
        when(repository.findAllByWorkshopId(workshopId))
                .thenReturn(List.of());

        service.getAll(new SearchWorkResultRequestDTO(null));

        verify(repository).findAllByWorkshopId(workshopId);
    }

    @Test
    void getRejectsRecordFromAnotherWorkshop() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndWorkshopId(id, workshopId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(EntityNotFoundException.class);
    }

}

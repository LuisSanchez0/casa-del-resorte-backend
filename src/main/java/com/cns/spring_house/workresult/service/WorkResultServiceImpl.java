package com.cns.spring_house.workresult.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.transformation.repository.TransformationRepository;
import com.cns.spring_house.workresult.dto.SearchWorkResultRequestDTO;
import com.cns.spring_house.workresult.dto.WorkResultResponseDTO;
import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workresult.entity.WorkResultStatus;
import com.cns.spring_house.workresult.mapper.WorkResultMapper;
import com.cns.spring_house.workresult.repository.WorkResultRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class WorkResultServiceImpl implements WorkResultService {

    private final WorkResultRepository repository;
    private final WorkResultMapper mapper;
    private final TransformationRepository transformationRepository;

    @Override
    public List<WorkResultResponseDTO> getAll(
            SearchWorkResultRequestDTO requestDTO) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        List<WorkResult> workResults =
                resolveWorkResults(workshopId, requestDTO.status());
        Map<UUID, BigDecimal> suggestions = resolveSuggestedPrices(workResults);

        return workResults.stream()
                .map(workResult -> mapper.toResponse(
                        workResult, suggestedPriceOf(workResult, suggestions)))
                .toList();
    }

    @Override
    public WorkResultResponseDTO get(UUID id) {
        WorkResult workResult = repository
                .findByIdAndWorkshopId(id, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el registro especificado"));
        Map<UUID, BigDecimal> suggestions =
                resolveSuggestedPrices(List.of(workResult));

        return mapper.toResponse(
                workResult, suggestedPriceOf(workResult, suggestions));
    }

    /**
     * Precio de la pieza de ENTRADA de cada transformacion, para toda la lista en
     * UNA sola consulta. Un JOB no participa: no tiene pieza de origen.
     */
    private Map<UUID, BigDecimal> resolveSuggestedPrices(
            List<WorkResult> workResults) {
        Set<UUID> outputItemIds = workResults.stream()
                .filter(workResult -> workResult.getItem() != null)
                .map(workResult -> workResult.getItem().getId())
                .collect(Collectors.toSet());

        if (outputItemIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, BigDecimal> prices = new HashMap<>();
        for (Object[] row : transformationRepository
                .findInputPricesByOutputItemIds(outputItemIds)) {
            prices.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return prices;
    }

    /**
     * Cero NO es un precio: es el centinela de "sin configurar" que la propia
     * transformacion usa al crear el inventario de salida. Sugerirlo pondria un
     * Q 0.00 enganoso en la caja, asi que se devuelve null.
     */
    private BigDecimal suggestedPriceOf(
            WorkResult workResult, Map<UUID, BigDecimal> suggestions) {
        if (workResult.getItem() == null) {
            return null;
        }

        BigDecimal price = suggestions.get(workResult.getItem().getId());

        return price != null && price.signum() > 0 ? price : null;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createPendingFromJobLog(JobLog jobLog) {
        if (!jobLog.getJob().getWorkshop().getId()
                .equals(SecurityUtils.getWorkshopId())) {
            throw new ValidationException(
                    "El servicio no pertenece al taller del usuario");
        }
        if (repository.existsByJobLogId(jobLog.getId())) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un registro para el trabajo realizado");
        }
        WorkResult workResult = WorkResult.builder()
                .workshop(jobLog.getJob().getWorkshop())
                .jobLog(jobLog)
                .build();
        repository.save(workResult);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createPendingFromItem(InventoryItem item) {
        if (!item.getInventory().getWorkshop().getId()
                .equals(SecurityUtils.getWorkshopId())) {
            throw new ValidationException(
                    "El item no pertenece al taller del usuario");
        }
        if (repository.existsByItemId(item.getId())) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un registro para el item generado");
        }
        WorkResult workResult = WorkResult.builder()
                .workshop(item.getInventory().getWorkshop())
                .item(item)
                .build();
        repository.save(workResult);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkResult resolvePending(UUID id, UUID productId, UUID itemId) {
        WorkResult workResult = repository
                .findByIdAndWorkshopIdAndStatus(
                        id,
                        SecurityUtils.getWorkshopId(),
                        WorkResultStatus.PENDING)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe un trabajo pendiente con el id especificado"));
        validateSource(workResult, productId, itemId);
        return workResult;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markAsSold(UUID id, UUID productId, UUID itemId) {
        WorkResult workResult = repository
                .findForUpdate(
                        id,
                        SecurityUtils.getWorkshopId(),
                        WorkResultStatus.PENDING)
                .orElseThrow(() -> new ValidationException(
                        "El trabajo pendiente ya fue vendido o no esta disponible"));
        validateSource(workResult, productId, itemId);
        workResult.setStatus(WorkResultStatus.SOLD);
    }

    /**
     * Se busca por id y taller (sin bloqueo): a diferencia de
     * {@link #markAsSold}, la reversion corre dentro de la transaccion que ya
     * tiene la Sale bloqueada por su propio ciclo de vida, y esta operacion no
     * compite por vender el mismo trabajo dos veces sino por deshacer una
     * venta ya unica.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreToPending(UUID id) {
        WorkResult workResult = repository
                .findByIdAndWorkshopId(id, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el registro especificado"));
        if (workResult.getStatus() != WorkResultStatus.SOLD) {
            throw new ValidationException(
                    "El trabajo especificado no se encuentra vendido");
        }
        workResult.setStatus(WorkResultStatus.PENDING);
    }

    private List<WorkResult> resolveWorkResults(
            UUID workshopId, WorkResultStatus status) {
        if (status == WorkResultStatus.PENDING) {
            return repository.findAvailableByWorkshopIdAndStatus(
                    workshopId, status);
        }
        if (status != null) {
            return repository.findAllByWorkshopIdAndStatus(workshopId, status);
        }
        return repository.findAllByWorkshopId(workshopId);
    }

    private void validateSource(
            WorkResult workResult, UUID productId, UUID itemId) {
        if (workResult.getJobLog() != null) {
            validateJobLogSource(workResult, productId, itemId);
            return;
        }
        validateItemSource(workResult, itemId);
    }

    private void validateJobLogSource(
            WorkResult workResult, UUID productId, UUID itemId) {
        if (itemId != null) {
            throw new ValidationException(
                    "El trabajo pendiente no corresponde a un item de inventario");
        }
        UUID jobProductId = workResult.getJobLog()
                .getJob().getProduct().getId();
        if (!jobProductId.equals(productId)) {
            throw new ValidationException(
                    "El trabajo pendiente no corresponde al producto vendido");
        }
    }

    private void validateItemSource(WorkResult workResult, UUID itemId) {
        InventoryItem item = workResult.getItem();
        if (itemId == null || !item.getId().equals(itemId)) {
            throw new ValidationException(
                    "El trabajo pendiente no corresponde al item vendido");
        }
        if (!item.getInventory().getWorkshop().getId()
                .equals(SecurityUtils.getWorkshopId())) {
            throw new ValidationException(
                    "El item no pertenece al taller del usuario");
        }
    }

}

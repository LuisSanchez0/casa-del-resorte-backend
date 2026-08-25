package com.cns.spring_house.workresult.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.workresult.dto.SearchWorkResultRequestDTO;
import com.cns.spring_house.workresult.dto.WorkResultResponseDTO;
import com.cns.spring_house.workresult.entity.WorkResult;

public interface WorkResultService {

    List<WorkResultResponseDTO> getAll(SearchWorkResultRequestDTO requestDTO);

    WorkResultResponseDTO get(UUID id);

    void createPendingFromJobLog(JobLog jobLog);

    void createPendingFromItem(InventoryItem item);

    WorkResult resolvePending(UUID id, UUID productId, UUID itemId);

    void markAsSold(UUID id, UUID productId, UUID itemId);

    /**
     * Inverso de {@link #markAsSold}: devuelve un trabajo SOLD a PENDING
     * porque la venta que lo vendio se revirtio. Lo usa exclusivamente la
     * reversion de una venta COMPLETED; cancelar una venta PENDING nunca llega
     * a marcar SOLD, asi que no pasa por aqui.
     */
    void restoreToPending(UUID id);

}

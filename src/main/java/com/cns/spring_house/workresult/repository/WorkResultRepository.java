package com.cns.spring_house.workresult.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workresult.entity.WorkResultStatus;

import jakarta.persistence.LockModeType;

public interface WorkResultRepository
        extends JpaRepository<WorkResult, UUID> {

    @Query("""
        SELECT workResult
        FROM WorkResult workResult
        LEFT JOIN workResult.jobLog jobLog
        LEFT JOIN workResult.item item
        WHERE workResult.workshop.id = :workshopId
        ORDER BY COALESCE(
            jobLog.performedDate,
            item.createdAt,
            workResult.createdAt) DESC
    """)
    List<WorkResult> findAllByWorkshopId(@Param("workshopId") UUID workshopId);

    @Query("""
        SELECT workResult
        FROM WorkResult workResult
        LEFT JOIN workResult.jobLog jobLog
        LEFT JOIN workResult.item item
        WHERE workResult.workshop.id = :workshopId
          AND workResult.status = :status
        ORDER BY COALESCE(
            jobLog.performedDate,
            item.createdAt,
            workResult.createdAt) DESC
    """)
    List<WorkResult> findAllByWorkshopIdAndStatus(
            @Param("workshopId") UUID workshopId,
            @Param("status") WorkResultStatus status);

    @Query("""
        SELECT workResult
        FROM WorkResult workResult
        LEFT JOIN workResult.jobLog jobLog
        LEFT JOIN workResult.item item
        WHERE workResult.workshop.id = :workshopId
          AND workResult.status = :status
          AND NOT EXISTS (
              SELECT detail
              FROM SaleDetail detail
              WHERE detail.workResult = workResult)
        ORDER BY COALESCE(
            jobLog.performedDate,
            item.createdAt,
            workResult.createdAt) DESC
    """)
    List<WorkResult> findAvailableByWorkshopIdAndStatus(
            @Param("workshopId") UUID workshopId,
            @Param("status") WorkResultStatus status);

    Optional<WorkResult> findByIdAndWorkshopId(UUID id, UUID workshopId);

    Optional<WorkResult> findByIdAndWorkshopIdAndStatus(
            UUID id, UUID workshopId, WorkResultStatus status);

    boolean existsByJobLogId(UUID jobLogId);

    boolean existsByItemId(UUID itemId);

    /** El trabajo que una transformacion creo para su pieza de salida. */
    Optional<WorkResult> findByItemId(UUID itemId);

    /** El trabajo que un servicio ejecutado creo. */
    Optional<WorkResult> findByJobLogId(UUID jobLogId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT workResult
        FROM WorkResult workResult
        WHERE workResult.id = :id
          AND workResult.workshop.id = :workshopId
          AND workResult.status = :status
    """)
    Optional<WorkResult> findForUpdate(
            @Param("id") UUID id,
            @Param("workshopId") UUID workshopId,
            @Param("status") WorkResultStatus status);

}

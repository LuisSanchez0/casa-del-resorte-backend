package com.cns.spring_house.dailylog.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.dailylog.entity.DailyLog;
import com.cns.spring_house.dailylog.repository.projection.DailyLogsByDayProjection;

public interface DailyLogRepository extends JpaRepository<DailyLog, UUID>, JpaSpecificationExecutor<DailyLog> {

    /**
     * Todo acceso a un registro concreto pasa por aqui: buscar solo por id
     * permitia leer o editar la bitacora de otro taller conociendo el UUID,
     * igual que ocurria en {@code SaleRepository} antes de acotarlo.
     */
    Optional<DailyLog> findByIdAndWorkshopId(UUID id, UUID workshopId);

    /**
     * Una venta solo puede tener un registro (uq_daily_log_sale). Comprobarlo
     * antes de guardar convierte el segundo intento en un 400 con mensaje en vez
     * de en una violacion de constraint traducida a 500.
     */
    boolean existsBySaleId(UUID saleId);

    // ── Agregados del dashboard de secretaria ─────────────────────
    //
    // Se cuenta en la base de datos: el dashboard NUNCA descarga la bitacora
    // para contarla en el navegador, igual que el de ventas y el cierre de
    // caja. El taller es siempre parametro obligatorio y viaja en el SQL, de
    // modo que el aislamiento ocurre en la sentencia y no filtrando despues.
    //
    // La fecha que decide todo aqui es `performed_date`, no `created_at`: es la
    // fecha con la que la secretaria levanta el registro, y es la unica
    // comparable con el dia de negocio del resto del panel.

    /** Bitacoras del taller entero en una fecha, sean de quien sean. */
    long countByWorkshopIdAndPerformedDate(UUID workshopId, LocalDate performedDate);

    /**
     * Bitacoras del empleado autenticado agrupadas por dia, dentro de un rango
     * con AMBOS extremos inclusivos: `performed_date` es DATE, no un instante,
     * asi que el ultimo dia pertenece al rango (mismo criterio que
     * {@code CashClosureRepository} con `business_date`, y distinto del de las
     * ventanas `[from, to)` sobre `completed_at`).
     *
     * Devuelve SOLO los dias con actividad; el servicio rellena los ceros para
     * que la serie sea densa.
     */
    @Query("""
        SELECT log.performedDate AS logDate, COUNT(log) AS logCount
        FROM DailyLog log
        WHERE log.workshop.id = :workshopId
          AND log.employee.id = :employeeId
          AND log.performedDate >= :from
          AND log.performedDate <= :to
        GROUP BY log.performedDate
        ORDER BY log.performedDate
    """)
    List<DailyLogsByDayProjection> findMyDailyLogsByDay(
            @Param("workshopId") UUID workshopId,
            @Param("employeeId") UUID employeeId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

}

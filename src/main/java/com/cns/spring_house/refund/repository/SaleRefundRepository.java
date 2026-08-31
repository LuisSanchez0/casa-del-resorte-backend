package com.cns.spring_house.refund.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.refund.entity.SaleRefund;
import com.cns.spring_house.refund.repository.projection.RefundDetailProjection;
import com.cns.spring_house.refund.repository.projection.RefundMethodStatProjection;

/**
 * Las devoluciones, leidas SIEMPRE por taller y por ventana de `effective_at`.
 *
 * Las dos consultas del cierre estan acotadas al periodo y no crecen con el
 * numero de dias ni provocan una consulta por devolucion: una agrega por forma
 * de devolucion y la otra trae el detalle que la pantalla lista.
 */
public interface SaleRefundRepository extends JpaRepository<SaleRefund, UUID> {

    /** Si esta venta ya tiene devolucion. Respalda el UNIQUE de la tabla. */
    boolean existsBySaleId(UUID saleId);

    /**
     * Lo devuelto por forma de devolucion en `[from, to)`.
     *
     * Semiabierto como el resto del cierre: una devolucion a las 00:00:00 del
     * dia siguiente pertenece al dia siguiente, no a los dos.
     */
    @Query(nativeQuery = true, value = """
        SELECT refund.payment_method AS method,
               COUNT(*) AS refundCount,
               COALESCE(SUM(refund.amount), 0) AS amount
        FROM sale_refund refund
        WHERE refund.workshop_id = :workshopId
          AND refund.effective_at >= :from
          AND refund.effective_at < :to
        GROUP BY refund.payment_method
    """)
    List<RefundMethodStatProjection> summarizeRefundMethodsBetween(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * El detalle de las devoluciones del periodo, ya ordenado.
     *
     * El orden termina en `refund.id` para que sea total: dos devoluciones del
     * mismo instante y el mismo importe podrian alternarse entre peticiones
     * identicas, y el snapshot quedaria distinto segun el plan de ejecucion.
     */
    @Query(nativeQuery = true, value = """
        SELECT refund.id AS refundId,
               refund.sale_id AS saleId,
               refund.amount AS amount,
               refund.effective_at AS effectiveAt,
               refund.payment_method AS paymentMethod,
               TRIM(employee.first_name || ' ' || employee.last_name) AS performedByName,
               refund.reason AS reason
        FROM sale_refund refund
        JOIN employee ON employee.id = refund.performed_by
        WHERE refund.workshop_id = :workshopId
          AND refund.effective_at >= :from
          AND refund.effective_at < :to
        ORDER BY refund.effective_at ASC, refund.id ASC
    """)
    List<RefundDetailProjection> findRefundsBetween(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

}

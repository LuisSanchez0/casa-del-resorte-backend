package com.cns.spring_house.cashclosure.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.cashclosure.dto.CashClosureDaySummaryDTO;
import com.cns.spring_house.cashclosure.entity.CashClosure;
import com.cns.spring_house.cashclosure.repository.projection.CashClosureStampProjection;
import com.cns.spring_house.cashclosure.repository.projection.CashClosureSummaryProjection;

public interface CashClosureRepository extends JpaRepository<CashClosure, UUID> {

    /**
     * Existencia del cierre de una fecha operativa. La usan el propio cierre
     * —para dar un mensaje entendible antes de chocar con el UNIQUE— y el
     * bloqueo de nuevas ventas.
     */
    boolean existsByWorkshopIdAndBusinessDate(UUID workshopId, LocalDate businessDate);

    Optional<CashClosure> findByWorkshopIdAndBusinessDate(
            UUID workshopId, LocalDate businessDate);

    /**
     * Lo minimo para responder "esta caja ya se cerro": el identificador del
     * cierre y el instante en que se confirmo.
     *
     * Existe aparte del anterior porque el dashboard de secretaria solo
     * necesita eso, y cargar la entidad para descartarla casi entera cuesta dos
     * SELECT de mas: taller y empleado que cerro son {@code @ManyToOne} EAGER.
     *
     * `closedAt` es `createdAt` por la razon que explica {@code CashClosure}: la
     * fila se inserta al cerrar y nunca se modifica.
     */
    @Query("""
        SELECT closure.id AS id, closure.createdAt AS closedAt
        FROM CashClosure closure
        WHERE closure.workshop.id = :workshopId
          AND closure.businessDate = :businessDate
    """)
    Optional<CashClosureStampProjection> findStampByWorkshopIdAndBusinessDate(
            @Param("workshopId") UUID workshopId,
            @Param("businessDate") LocalDate businessDate);

    /**
     * Nunca se lee un cierre solo por id: con el UUID a mano, `findById` dejaria
     * leer y exportar el cierre de otro taller. Mismo criterio que
     * {@code SaleRepository.findByIdAndWorkshopId}.
     */
    Optional<CashClosure> findByIdAndWorkshopId(UUID id, UUID workshopId);

    /**
     * Cierres del rango, del mas reciente al mas antiguo.
     *
     * `business_date` es DATE, no timestamp: aqui los extremos SI son inclusivos
     * —un cierre del dia `to` pertenece al rango `to`—, a diferencia de las
     * ventanas `[from, to)` que se aplican sobre `completed_at` y `paid_at`,
     * donde el limite superior es un instante y tiene que quedar fuera.
     *
     * El desempate por id hace el orden determinista cuando dos filas comparten
     * fecha, cosa que el UNIQUE(workshop, business_date) impide dentro de un
     * taller pero que igualmente evita que el orden dependa del plan de
     * ejecucion.
     */
    List<CashClosure> findByWorkshopIdAndBusinessDateBetweenOrderByBusinessDateDescIdDesc(
            UUID workshopId, LocalDate from, LocalDate to);

    /**
     * Agregado del rango, sumado por la base de datos. No se descargan los
     * cierres para recorrerlos en Java, igual que en el dashboard de ventas.
     *
     * Los tres contadores de cuadre salen de un CASE sobre el signo de
     * `difference`, que es exactamente la regla que aplica
     * {@code CashBalanceStatus.of(...)}: cero cuadra, negativo falta, positivo
     * sobra.
     */
    @Query("""
        SELECT COUNT(closure) AS closureCount,
               COALESCE(SUM(closure.invoiceCount), 0) AS invoiceCount,
               COALESCE(SUM(closure.invoiceTotal), 0) AS invoiceTotal,
               COALESCE(SUM(closure.exchangeInvoiceCount), 0) AS exchangeInvoiceCount,
               COALESCE(SUM(closure.exchangeInvoiceTotal), 0) AS exchangeInvoiceTotal,
               COALESCE(SUM(closure.totalInvoiced), 0) AS totalInvoiced,
               COALESCE(SUM(closure.cashPaymentCount), 0) AS cashPaymentCount,
               COALESCE(SUM(closure.cashPayments), 0) AS cashPayments,
               COALESCE(SUM(closure.cardPaymentCount), 0) AS cardPaymentCount,
               COALESCE(SUM(closure.cardPayments), 0) AS cardPayments,
               COALESCE(SUM(closure.transferPaymentCount), 0) AS transferPaymentCount,
               COALESCE(SUM(closure.transferPayments), 0) AS transferPayments,
               COALESCE(SUM(closure.totalCollected), 0) AS totalCollected,
               COALESCE(SUM(closure.exchangeCollectedCount), 0) AS exchangeCollectedCount,
               COALESCE(SUM(closure.exchangeCollectedAmount), 0) AS exchangeCollectedAmount,
               COALESCE(SUM(CASE WHEN closure.difference = 0 THEN 1 ELSE 0 END), 0)
                   AS balancedClosures,
               COALESCE(SUM(CASE WHEN closure.difference < 0 THEN 1 ELSE 0 END), 0)
                   AS shortClosures,
               COALESCE(SUM(CASE WHEN closure.difference > 0 THEN 1 ELSE 0 END), 0)
                   AS overClosures,
               COALESCE(SUM(closure.difference), 0) AS netCashDifference
        FROM CashClosure closure
        WHERE closure.workshop.id = :workshopId
          AND closure.businessDate >= :from
          AND closure.businessDate <= :to
    """)
    CashClosureSummaryProjection summarizeRange(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Saldos cambiarios pendientes del rango, del cierre mas reciente al mas
     * antiguo. El servicio pide UNA sola fila con un Pageable: el saldo final
     * del rango es el del ultimo cierre, no la suma de los diarios.
     *
     * Se resuelve con una consulta propia y no leyendo la lista completa para
     * que el resumen no dependa de haber cargado antes todos los cierres.
     */
    @Query("""
        SELECT closure.pendingExchangeAmount
        FROM CashClosure closure
        WHERE closure.workshop.id = :workshopId
          AND closure.businessDate >= :from
          AND closure.businessDate <= :to
        ORDER BY closure.businessDate DESC, closure.id DESC
    """)
    List<BigDecimal> findPendingExchangeAmounts(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            Pageable pageable);

    /**
     * Filas de la tabla diaria del resumen. Es una proyeccion y no la entidad
     * porque el reporte solo necesita ocho columnas por dia, y porque cargar
     * cada `CashClosure` arrastraria su taller y su empleado.
     *
     * Solo hay fila para los dias que tienen cierre: un dia sin cerrar no se
     * inventa en cero.
     *
     * Ordena ASCENDENTE, al reves que el listado de cierres. No es un descuido:
     * el listado es una bandeja donde lo ultimo cerrado es lo que se busca,
     * mientras que esto es el detalle de un periodo, y un mes se lee del dia 1
     * al 31, de arriba abajo.
     */
    @Query("""
        SELECT new com.cns.spring_house.cashclosure.dto.CashClosureDaySummaryDTO(
            closure.id,
            closure.businessDate,
            closure.totalInvoiced,
            closure.totalCollected,
            closure.cashPayments,
            closure.cardPayments,
            closure.transferPayments,
            closure.difference)
        FROM CashClosure closure
        WHERE closure.workshop.id = :workshopId
          AND closure.businessDate >= :from
          AND closure.businessDate <= :to
        ORDER BY closure.businessDate ASC, closure.id ASC
    """)
    List<CashClosureDaySummaryDTO> findDaySummaries(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

}

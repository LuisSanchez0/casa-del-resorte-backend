package com.cns.spring_house.cashclosure.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.cashclosure.entity.CashClosure;
import com.cns.spring_house.cashclosure.entity.CashClosureRefund;
import com.cns.spring_house.cashclosure.repository.projection.CashClosureItemProjection;
import com.cns.spring_house.cashclosure.repository.projection.CashClosurePaymentMethodProjection;
import com.cns.spring_house.cashclosure.repository.projection.CashClosureStampProjection;
import com.cns.spring_house.cashclosure.repository.projection.CashClosureSummaryProjection;

public interface CashClosureRepository extends JpaRepository<CashClosure, UUID> {

    /**
     * Existencia del cierre de una fecha operativa. La usan el propio cierre
     * —para dar un mensaje entendible antes de chocar con el UNIQUE— y el
     * bloqueo de nuevas ventas y cobros.
     */
    boolean existsByWorkshopIdAndBusinessDate(UUID workshopId, LocalDate businessDate);

    /**
     * El cierre de una fecha.
     *
     * El detalle NO se une aqui: las tres colecciones se acotan por lote en la
     * propia entidad. Unirlas seria imposible —Hibernate rechaza fusionar varias
     * colecciones— y multiplicaria las filas del resultado. Ver
     * {@code CashClosure.items}.
     */
    Optional<CashClosure> findByWorkshopIdAndBusinessDate(
            UUID workshopId, LocalDate businessDate);

    /**
     * Lo minimo para responder "esta caja ya se cerro": el identificador del
     * cierre y el instante en que se confirmo.
     *
     * Existe aparte del anterior porque el dashboard de secretaria solo
     * necesita eso, y cargar la entidad con su detalle para descartarla casi
     * entera cuesta varias consultas de mas.
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
     * leer el cierre de otro taller. Mismo criterio que
     * {@code SaleRepository.findByIdAndWorkshopId}.
     */
    Optional<CashClosure> findByIdAndWorkshopId(UUID id, UUID workshopId);

    // ── Cierre mensual ───────────────────────────────────────────
    //
    // El mes se agrega sobre los SNAPSHOTS diarios y no contra `sale`. Es lo
    // que preserva la verdad historica: recalcularlo contra las ventas actuales
    // haria que cobrar hoy una cambiaria de marzo cambiara el cierre de marzo.
    //
    // Son CUATRO consultas para todo el mes —escalares, articulos, documentos y
    // formas de pago—, no una por dia.

    /**
     * Los escalares del mes. `business_date` es DATE, asi que los extremos son
     * inclusivos, a diferencia de las ventanas `[from, to)` sobre instantes.
     */
    @Query("""
        SELECT COUNT(closure) AS closureCount,
               COALESCE(SUM(closure.invoiceCount), 0) AS invoiceCount,
               COALESCE(SUM(closure.invoiceTotal), 0) AS invoiceTotal,
               COALESCE(SUM(closure.exchangeInvoiceCount), 0) AS exchangeInvoiceCount,
               COALESCE(SUM(closure.exchangeInvoiceTotal), 0) AS exchangeInvoiceTotal,
               COALESCE(SUM(closure.totalInvoiced), 0) AS totalInvoiced,
               COALESCE(SUM(closure.totalCollected), 0) AS totalCollected,
               COALESCE(SUM(closure.exchangeCollectedCount), 0) AS exchangeCollectedCount,
               COALESCE(SUM(closure.exchangeCollectedAmount), 0) AS exchangeCollectedAmount,
               COALESCE(SUM(closure.previousExchangeCollectedAmount), 0)
                   AS previousExchangeCollectedAmount,
               COALESCE(SUM(closure.issuedExchangePendingAmount), 0)
                   AS issuedExchangePendingAmount,
               COALESCE(SUM(closure.itemsBilledAmount), 0) AS itemsBilledAmount,
               COALESCE(SUM(closure.refundCount), 0) AS refundCount,
               COALESCE(SUM(closure.totalRefunded), 0) AS totalRefunded,
               COALESCE(SUM(closure.refundCashCount), 0) AS refundCashCount,
               COALESCE(SUM(closure.refundCash), 0) AS refundCash,
               COALESCE(SUM(closure.refundCardCount), 0) AS refundCardCount,
               COALESCE(SUM(closure.refundCard), 0) AS refundCard,
               COALESCE(SUM(closure.refundTransferCount), 0) AS refundTransferCount,
               COALESCE(SUM(closure.refundTransfer), 0) AS refundTransfer
        FROM CashClosure closure
        WHERE closure.workshop.id = :workshopId
          AND closure.businessDate >= :from
          AND closure.businessDate <= :to
    """)
    CashClosureSummaryProjection summarizeMonth(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Los articulos del mes, reagregados por producto sobre el detalle diario.
     *
     * Un mismo producto vendido en quince dias distintos tiene quince filas de
     * detalle; aqui vuelve como UNA con la cantidad y el importe del mes.
     *
     * Desde V38 tambien puede tener VARIAS filas dentro de un mismo dia, una por
     * forma de cobro. No hace falta cambiar nada: este GROUP BY ya agrupaba por
     * `productId` y las suma igual, que es lo correcto —el mes responde que se
     * vendio, no como se pago cada unidad—. Quien si separa por via de cobro es
     * el resumen de productos, y lo hace sobre las filas del dia.
     */
    @Query("""
        SELECT item.productId AS productId,
               MAX(item.itemName) AS itemName,
               MAX(CAST(item.itemType AS String)) AS itemType,
               COALESCE(SUM(item.quantity), 0) AS quantity,
               COALESCE(SUM(item.billedAmount), 0) AS billedAmount
        FROM CashClosureItem item
        WHERE item.cashClosure.workshop.id = :workshopId
          AND item.cashClosure.businessDate >= :from
          AND item.cashClosure.businessDate <= :to
        GROUP BY item.productId
        ORDER BY COALESCE(SUM(item.billedAmount), 0) DESC,
                 COALESCE(SUM(item.quantity), 0) DESC,
                 item.productId ASC
    """)
    List<CashClosureItemProjection> findMonthlyItems(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Los documentos cambiarios del mes de UN papel concreto (emitidos o
     * cobrados), tal y como los congelo cada cierre diario.
     *
     * Se lee del snapshot y no de `sale`: el estado que interesa es el que
     * tenian al cerrar su dia.
     */
    @Query("""
        SELECT invoice
        FROM CashClosureExchangeInvoice invoice
        WHERE invoice.cashClosure.workshop.id = :workshopId
          AND invoice.cashClosure.businessDate >= :from
          AND invoice.cashClosure.businessDate <= :to
          AND invoice.scope = :scope
        ORDER BY invoice.issuedAt ASC, invoice.saleId ASC
    """)
    List<com.cns.spring_house.cashclosure.entity.CashClosureExchangeInvoice>
            findMonthlyExchangeInvoices(
                    @Param("workshopId") UUID workshopId,
                    @Param("from") LocalDate from,
                    @Param("to") LocalDate to,
                    @Param("scope")
                    com.cns.spring_house.cashclosure.entity.ExchangeInvoiceScope scope);

    /** Lo cobrado en el mes por forma de pago, sumado sobre el detalle diario. */
    @Query("""
        SELECT CAST(total.paymentMethod AS String) AS paymentMethod,
               COALESCE(SUM(total.paymentCount), 0) AS paymentCount,
               COALESCE(SUM(total.amount), 0) AS amount
        FROM CashClosurePaymentMethodTotal total
        WHERE total.cashClosure.workshop.id = :workshopId
          AND total.cashClosure.businessDate >= :from
          AND total.cashClosure.businessDate <= :to
        GROUP BY total.paymentMethod
    """)
    List<CashClosurePaymentMethodProjection> findMonthlyPaymentMethods(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Las devoluciones de los cierres CONFIRMADOS del mes.
     *
     * Se leen de los snapshots y no de `sale_refund` a proposito: releer los
     * eventos vivos de un dia ya cerrado permitiria que una devolucion
     * registrada despues apareciera en un mes que ya estaba dicho. El mes
     * cuenta lo que sus dias contaron.
     *
     * Una sola consulta para todo el mes, no una por dia.
     */
    @Query("""
        SELECT refund
        FROM CashClosureRefund refund
        WHERE refund.cashClosure.workshop.id = :workshopId
          AND refund.cashClosure.businessDate >= :from
          AND refund.cashClosure.businessDate <= :to
        ORDER BY refund.effectiveAt ASC, refund.refundId ASC
    """)
    List<CashClosureRefund> findMonthlyRefunds(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Fechas del mes que ya tienen cierre, para calcular los dias pendientes. */
    @Query("""
        SELECT closure.businessDate
        FROM CashClosure closure
        WHERE closure.workshop.id = :workshopId
          AND closure.businessDate >= :from
          AND closure.businessDate <= :to
        ORDER BY closure.businessDate ASC
    """)
    List<LocalDate> findClosedDates(
            @Param("workshopId") UUID workshopId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

}

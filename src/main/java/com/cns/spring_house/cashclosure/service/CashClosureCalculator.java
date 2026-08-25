package com.cns.spring_house.cashclosure.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureReceivablesDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.InvoiceTypeStatProjection;
import com.cns.spring_house.sale.repository.projection.PaymentMethodStatProjection;
import com.cns.spring_house.sale.repository.projection.SalesSummaryProjection;

import lombok.RequiredArgsConstructor;

/**
 * Calcula las cifras de un dia contra `sale`. Es el UNICO sitio donde se decide
 * que entra en un cierre de caja.
 *
 * Tres reglas gobiernan todo lo de aqui:
 *
 * <pre>
 * FACTURADO  -> status COMPLETED  y completed_at dentro del dia
 * COBRADO    -> payment_status PAID y paid_at dentro del dia
 * EFECTIVO   -> de lo cobrado, solo payment_method = CASH
 * </pre>
 *
 * Son dimensiones distintas y no tienen por que cuadrar entre si. Una cambiaria
 * emitida hoy por Q10,000 y aun sin cobrar suma Q10,000 facturados, Q0 cobrados
 * y Q10,000 de cartera; cuando se cobre tres dias despues sumara Q0 a la
 * facturacion de ESE dia y Q10,000 a sus cobros.
 *
 * Todas las cifras las agrega la base de datos. Nunca se descarga el historico
 * de ventas para sumarlo aqui, igual que en el dashboard de ventas.
 *
 * PENDING, CANCELLED y REVERSED quedan fuera en el propio SQL: una venta que no
 * llego a emitirse o que se deshizo no cuadra ninguna caja.
 */
@Component
@RequiredArgsConstructor
public class CashClosureCalculator {

    private final SaleRepository saleRepository;

    /**
     * Cifras del dia para el taller indicado.
     *
     * La ventana es semiabierta `[dayStart, dayEnd)`: el limite superior es el
     * arranque del dia siguiente, nunca un 23:59:59.999 artificial que dejaria
     * fuera lo ocurrido en el ultimo milisegundo.
     */
    public DailyCashFigures calculate(UUID workshopId, LocalDate businessDate) {
        LocalDateTime dayStart = businessDate.atStartOfDay();
        LocalDateTime dayEnd = businessDate.plusDays(1).atStartOfDay();

        return new DailyCashFigures(
                businessDate,
                invoicing(workshopId, dayStart, dayEnd),
                collections(workshopId, dayStart, dayEnd),
                receivables(workshopId, dayStart, dayEnd));
    }

    /** Lo emitido hoy, por tipo de documento y por `completedAt`. */
    private CashClosureInvoicingDTO invoicing(
            UUID workshopId, LocalDateTime dayStart, LocalDateTime dayEnd) {

        Map<InvoiceType, InvoiceTypeStatProjection> byType =
                new EnumMap<>(InvoiceType.class);
        for (InvoiceTypeStatProjection row
                : saleRepository.findInvoicingBetween(workshopId, dayStart, dayEnd)) {
            // Una venta COMPLETED anterior a V20 puede no tener tipo de
            // documento: se agrupa en una fila con invoice_type NULL que no
            // corresponde a ninguna de las dos categorias del cierre.
            if (row.getInvoiceType() != null) {
                byType.put(InvoiceType.valueOf(row.getInvoiceType()), row);
            }
        }

        long invoiceCount = countOf(byType.get(InvoiceType.INVOICE));
        BigDecimal invoiceTotal = amountOf(byType.get(InvoiceType.INVOICE));
        long exchangeCount = countOf(byType.get(InvoiceType.EXCHANGE_INVOICE));
        BigDecimal exchangeTotal = amountOf(byType.get(InvoiceType.EXCHANGE_INVOICE));

        return new CashClosureInvoicingDTO(
                invoiceCount,
                invoiceTotal,
                exchangeCount,
                exchangeTotal,
                money(invoiceTotal.add(exchangeTotal)));
    }

    /** Lo cobrado hoy, por forma de pago y por `paidAt`. */
    private CashClosureCollectionsDTO collections(
            UUID workshopId, LocalDateTime dayStart, LocalDateTime dayEnd) {

        Map<PaymentMethod, PaymentMethodStatProjection> byMethod =
                new EnumMap<>(PaymentMethod.class);
        List<PaymentMethodStatProjection> rows =
                saleRepository.findPaymentMethodsBetween(workshopId, dayStart, dayEnd);
        for (PaymentMethodStatProjection row : rows) {
            // Las ventas anteriores a V20 se cobraron pero el sistema nunca
            // registro COMO: su fila llega con metodo NULL y no puede imputarse
            // a efectivo, tarjeta ni transferencia sin falsear el arqueo.
            if (row.getMethod() != null) {
                byMethod.put(PaymentMethod.valueOf(row.getMethod()), row);
            }
        }

        BigDecimal cash = amountOf(byMethod.get(PaymentMethod.CASH));
        BigDecimal card = amountOf(byMethod.get(PaymentMethod.CARD));
        BigDecimal transfer = amountOf(byMethod.get(PaymentMethod.TRANSFER));

        return new CashClosureCollectionsDTO(
                countOf(byMethod.get(PaymentMethod.CASH)), cash,
                countOf(byMethod.get(PaymentMethod.CARD)), card,
                countOf(byMethod.get(PaymentMethod.TRANSFER)), transfer,
                money(cash.add(card).add(transfer)));
    }

    /**
     * Cartera cambiaria: lo cobrado hoy de credito, cuanto de ello venia de
     * dias anteriores, y el saldo total que queda por cobrar.
     *
     * El saldo pendiente NO se limita a los documentos de hoy: es lo que el
     * taller tiene por cobrar en este instante, venga del dia que venga.
     */
    private CashClosureReceivablesDTO receivables(
            UUID workshopId, LocalDateTime dayStart, LocalDateTime dayEnd) {

        SalesSummaryProjection collected = saleRepository
                .summarizeExchangeInvoicesCollectedBetween(workshopId, dayStart, dayEnd);
        SalesSummaryProjection previous = saleRepository
                .summarizePreviousExchangeInvoicesCollectedBetween(
                        workshopId, dayStart, dayEnd);
        SalesSummaryProjection pending = saleRepository
                .summarizePendingExchangeInvoices(workshopId);

        return new CashClosureReceivablesDTO(
                countOf(collected),
                amountOf(collected),
                amountOf(previous),
                amountOf(pending));
    }

    // ── Normalizacion ────────────────────────────────────────────
    //
    // Un grupo sin filas no es un error: significa cero. Y `COALESCE(SUM(...),
    // 0)` puede volver con escala 0, asi que todo importe se normaliza a dos
    // decimales para que la respuesta JSON, el PDF y la columna NUMERIC(10,2)
    // digan lo mismo.

    private static long countOf(InvoiceTypeStatProjection row) {
        return row == null ? 0L : row.getSaleCount();
    }

    private static long countOf(PaymentMethodStatProjection row) {
        return row == null ? 0L : row.getSaleCount();
    }

    private static long countOf(SalesSummaryProjection row) {
        return row == null ? 0L : row.getSalesCount();
    }

    private static BigDecimal amountOf(InvoiceTypeStatProjection row) {
        return row == null ? money(BigDecimal.ZERO) : money(row.getAmount());
    }

    private static BigDecimal amountOf(PaymentMethodStatProjection row) {
        return row == null ? money(BigDecimal.ZERO) : money(row.getAmount());
    }

    private static BigDecimal amountOf(SalesSummaryProjection row) {
        return row == null ? money(BigDecimal.ZERO) : money(row.getAmount());
    }

    static BigDecimal money(BigDecimal amount) {
        return amount == null
                ? BigDecimal.ZERO.setScale(2)
                : amount.setScale(2, RoundingMode.HALF_UP);
    }

}

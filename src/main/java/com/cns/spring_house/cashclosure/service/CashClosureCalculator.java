package com.cns.spring_house.cashclosure.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureExchangeDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureRefundsDTO;
import com.cns.spring_house.cashclosure.dto.ClosureRefundDTO;
import com.cns.spring_house.cashclosure.dto.ClosureRefundMethodDTO;
import com.cns.spring_house.refund.repository.SaleRefundRepository;
import com.cns.spring_house.refund.repository.projection.RefundDetailProjection;
import com.cns.spring_house.refund.repository.projection.RefundMethodStatProjection;
import com.cns.spring_house.cashclosure.dto.ClosureExchangeInvoiceDTO;
import com.cns.spring_house.cashclosure.dto.ClosurePaymentMethodDTO;
import com.cns.spring_house.cashclosure.dto.ClosureSoldItemDTO;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.ClosureExchangeInvoiceProjection;
import com.cns.spring_house.sale.repository.projection.ClosureSoldItemProjection;
import com.cns.spring_house.sale.repository.projection.InvoiceTypeStatProjection;
import com.cns.spring_house.sale.repository.projection.PaymentMethodStatProjection;
import com.cns.spring_house.sale.repository.projection.SalesSummaryProjection;

import lombok.RequiredArgsConstructor;

/**
 * Calcula las cifras de un periodo contra `sale`. Es el UNICO sitio donde se
 * decide que entra en un cierre de caja.
 *
 * Dos reglas gobiernan todo lo de aqui, y son dimensiones DISTINTAS que no
 * tienen por que cuadrar entre si:
 *
 * <pre>
 * FACTURADO -> status COMPLETED   y completed_at dentro del periodo
 * COBRADO   -> payment_status PAID y paid_at    dentro del periodo
 * </pre>
 *
 * El caso que lo explica todo: una cambiaria emitida hoy por Q10,000 y aun sin
 * cobrar suma Q10,000 facturados, Q0 cobrados y Q10,000 pendientes; cuando se
 * cobre tres dias despues sumara Q0 a la facturacion de ESE dia y Q10,000 a sus
 * cobros. Ningun quetzal se cuenta dos veces y ninguno se pierde.
 *
 * EL TOTAL COBRADO SALE DE LOS COBROS, nunca de la facturacion. Deducirlo de lo
 * emitido contaria como ingreso el credito que nadie ha pagado, que es
 * exactamente el error que este cierre existe para no cometer.
 *
 * Todas las cifras las agrega la base de datos. Nunca se descarga el historico
 * de ventas para sumarlo aqui.
 *
 * PENDING, CANCELLED y REVERSED quedan fuera en el propio SQL: una venta que no
 * llego a emitirse o que se deshizo no cuadra ninguna caja.
 */
@Component
@RequiredArgsConstructor
public class CashClosureCalculator {

    private final SaleRepository saleRepository;
    private final SaleRefundRepository refundRepository;

    /**
     * Cifras de UN DIA. La ventana es semiabierta `[dayStart, dayEnd)`: el
     * limite superior es el arranque del dia siguiente, nunca un
     * 23:59:59.999 artificial que dejaria fuera lo ocurrido en el ultimo
     * milisegundo.
     */
    public PeriodCashFigures calculate(UUID workshopId, LocalDate businessDate) {
        return calculate(
                workshopId,
                businessDate,
                businessDate.atStartOfDay(),
                businessDate.plusDays(1).atStartOfDay());
    }

    /**
     * Cifras de un periodo arbitrario `[from, to)`.
     *
     * Existe separado del diario porque el mes lo necesita para reconstruir el
     * detalle —articulos y documentos— que los escalares de los cierres diarios
     * no pueden agregar.
     */
    public PeriodCashFigures calculate(
            UUID workshopId, LocalDate businessDate,
            LocalDateTime from, LocalDateTime to) {

        List<ClosureSoldItemDTO> items = soldItems(workshopId, from, to);

        return new PeriodCashFigures(
                businessDate,
                items,
                items.stream()
                        .map(ClosureSoldItemDTO::billedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(2, RoundingMode.HALF_UP),
                invoicing(workshopId, from, to),
                exchange(workshopId, from, to),
                collections(workshopId, from, to),
                refunds(workshopId, from, to));
    }

    // ── 1. Que se vendio ─────────────────────────────────────────

    /**
     * Los articulos facturados en el periodo, ya agregados por producto.
     *
     * Incluye los servicios: la pregunta es "que se facturo", y una instalacion
     * facturada es dinero facturado. La categoria viaja en cada fila para que la
     * pantalla los distinga sin que ninguna linea desaparezca.
     */
    private List<ClosureSoldItemDTO> soldItems(
            UUID workshopId, LocalDateTime from, LocalDateTime to) {

        List<ClosureSoldItemDTO> items = new ArrayList<>();
        for (ClosureSoldItemProjection row
                : saleRepository.findSoldItemsBetween(workshopId, from, to)) {
            items.add(new ClosureSoldItemDTO(
                    row.getProductId(),
                    row.getItemName(),
                    FamilyCategory.valueOf(row.getItemType()),
                    row.getQuantity(),
                    money(row.getBilledAmount())));
        }
        return List.copyOf(items);
    }

    // ── 2. Cuanto se emitio ──────────────────────────────────────

    /** Lo emitido en el periodo, por tipo de documento y por `completedAt`. */
    private CashClosureInvoicingDTO invoicing(
            UUID workshopId, LocalDateTime from, LocalDateTime to) {

        Map<InvoiceType, InvoiceTypeStatProjection> byType =
                new EnumMap<>(InvoiceType.class);
        for (InvoiceTypeStatProjection row
                : saleRepository.findInvoicingBetween(workshopId, from, to)) {
            // Una venta COMPLETED anterior a V20 puede no tener tipo de
            // documento: se agrupa en una fila con invoice_type NULL que no
            // corresponde a ninguna de las dos categorias del cierre.
            if (row.getInvoiceType() != null) {
                byType.put(InvoiceType.valueOf(row.getInvoiceType()), row);
            }
        }

        BigDecimal invoiceTotal = amountOf(byType.get(InvoiceType.INVOICE));
        BigDecimal exchangeTotal = amountOf(byType.get(InvoiceType.EXCHANGE_INVOICE));

        return new CashClosureInvoicingDTO(
                countOf(byType.get(InvoiceType.INVOICE)),
                invoiceTotal,
                countOf(byType.get(InvoiceType.EXCHANGE_INVOICE)),
                exchangeTotal,
                money(invoiceTotal.add(exchangeTotal)));
    }

    // ── 3. Que quedo a credito y que se cobro de credito ─────────

    /**
     * Las cambiarias del periodo, en sus dos papeles.
     *
     * Los conjuntos se calculan por separado y con criterios distintos —uno por
     * `completedAt`, otro por `paidAt`— porque son hechos distintos. Fundirlos
     * en una sola consulta obligaria a exigir que ambas fechas cayeran en la
     * misma ventana, y entonces una cambiaria del lunes cobrada el miercoles no
     * apareceria en ninguno de los dos dias.
     */
    private CashClosureExchangeDTO exchange(
            UUID workshopId, LocalDateTime from, LocalDateTime to) {

        List<ClosureExchangeInvoiceDTO> issued = exchangeList(
                saleRepository.findExchangeInvoicesIssuedBetween(workshopId, from, to));
        List<ClosureExchangeInvoiceDTO> collected = exchangeList(
                saleRepository.findExchangeInvoicesCollectedBetween(workshopId, from, to));

        SalesSummaryProjection collectedTotals = saleRepository
                .summarizeExchangeInvoicesCollectedBetween(workshopId, from, to);
        SalesSummaryProjection previousTotals = saleRepository
                .summarizePreviousExchangeInvoicesCollectedBetween(workshopId, from, to);
        SalesSummaryProjection issuedPending = saleRepository
                .summarizeIssuedExchangeInvoicesPendingBetween(workshopId, from, to);

        BigDecimal issuedAmount = issued.stream()
                .map(ClosureExchangeInvoiceDTO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CashClosureExchangeDTO(
                issued.size(),
                money(issuedAmount),
                amountOf(issuedPending),
                countOf(collectedTotals),
                amountOf(collectedTotals),
                amountOf(previousTotals),
                issued,
                collected);
    }

    private List<ClosureExchangeInvoiceDTO> exchangeList(
            List<ClosureExchangeInvoiceProjection> rows) {

        List<ClosureExchangeInvoiceDTO> invoices = new ArrayList<>();
        for (ClosureExchangeInvoiceProjection row : rows) {
            boolean collected = PaymentStatus.PAID.name().equals(row.getPaymentStatus());
            invoices.add(new ClosureExchangeInvoiceDTO(
                    row.getSaleId(),
                    DocumentCodes.of(row.getSaleId()),
                    row.getCustomerName(),
                    row.getIssuedAt(),
                    money(row.getAmount()),
                    collected,
                    collected ? row.getPaidAt() : null,
                    collected && row.getPaymentMethod() != null
                            ? PaymentMethod.valueOf(row.getPaymentMethod())
                            : null));
        }
        return List.copyOf(invoices);
    }

    // ── 4. Como entro el dinero ──────────────────────────────────

    /**
     * Lo cobrado en el periodo, por forma de pago y por `paidAt`.
     *
     * Se devuelve UNA fila por cada valor del enum, incluidas las que quedaron
     * en cero: la pantalla no tiene por que saber que formas de pago existen
     * para poder pintar la que falta.
     *
     * El total es la suma de las filas, y esa igualdad es el contrato del
     * cierre: si no cuadrara, alguna via de cobro se estaria perdiendo.
     */
    private CashClosureCollectionsDTO collections(
            UUID workshopId, LocalDateTime from, LocalDateTime to) {

        Map<PaymentMethod, PaymentMethodStatProjection> byMethod =
                new EnumMap<>(PaymentMethod.class);
        for (PaymentMethodStatProjection row
                : saleRepository.findCollectedPaymentMethodsBetween(workshopId, from, to)) {
            // Las ventas anteriores a V20 se cobraron pero el sistema nunca
            // registro COMO: su fila llega con metodo NULL y no puede imputarse
            // a ninguna forma de pago sin falsear el desglose.
            if (row.getMethod() != null) {
                byMethod.put(PaymentMethod.valueOf(row.getMethod()), row);
            }
        }

        List<ClosurePaymentMethodDTO> methods = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentMethod method : PaymentMethod.values()) {
            PaymentMethodStatProjection row = byMethod.get(method);
            BigDecimal amount = amountOf(row);
            methods.add(new ClosurePaymentMethodDTO(method, countOf(row), amount));
            total = total.add(amount);
        }

        return new CashClosureCollectionsDTO(List.copyOf(methods), money(total));
    }

    // ── 5. Que salio ─────────────────────────────────────────────

    /**
     * El dinero DEVUELTO en el periodo, por `effective_at`.
     *
     * La fecha que manda es la de la devolucion, nunca la de la venta que la
     * origino: una venta de agosto devuelta en septiembre es dinero que salio
     * en septiembre. Contarla en agosto obligaria a reescribir un cierre ya
     * confirmado, que es exactamente lo que este diseno impide.
     *
     * Dos consultas acotadas al periodo: una agrega por forma de devolucion y
     * la otra trae el detalle que la pantalla lista. Ninguna crece con el
     * numero de dias ni provoca una consulta por devolucion.
     */
    private CashClosureRefundsDTO refunds(
            UUID workshopId, LocalDateTime from, LocalDateTime to) {

        Map<PaymentMethod, RefundMethodStatProjection> byMethod =
                new EnumMap<>(PaymentMethod.class);
        for (RefundMethodStatProjection row
                : refundRepository.summarizeRefundMethodsBetween(workshopId, from, to)) {
            if (row.getMethod() != null) {
                byMethod.put(PaymentMethod.valueOf(row.getMethod()), row);
            }
        }

        List<ClosureRefundMethodDTO> methods = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        long count = 0L;
        for (PaymentMethod method : PaymentMethod.values()) {
            RefundMethodStatProjection row = byMethod.get(method);
            BigDecimal amount = row == null ? BigDecimal.ZERO : row.getAmount();
            long methodCount = row == null ? 0L : row.getRefundCount();
            methods.add(new ClosureRefundMethodDTO(method, methodCount, money(amount)));
            total = total.add(amount);
            count += methodCount;
        }

        List<ClosureRefundDTO> refunds = new ArrayList<>();
        for (RefundDetailProjection row
                : refundRepository.findRefundsBetween(workshopId, from, to)) {
            refunds.add(new ClosureRefundDTO(
                    row.getRefundId(),
                    row.getSaleId(),
                    DocumentCodes.of(row.getSaleId()),
                    money(row.getAmount()),
                    row.getEffectiveAt(),
                    PaymentMethod.valueOf(row.getPaymentMethod()),
                    row.getPerformedByName(),
                    row.getReason()));
        }

        return new CashClosureRefundsDTO(
                List.copyOf(methods), count, money(total), List.copyOf(refunds));
    }

    // ── Normalizacion ────────────────────────────────────────────
    //
    // Un grupo sin filas no es un error: significa cero. Y `COALESCE(SUM(...),
    // 0)` puede volver con escala 0, asi que todo importe se normaliza a dos
    // decimales para que la respuesta JSON, el PDF y la columna NUMERIC digan lo
    // mismo.

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

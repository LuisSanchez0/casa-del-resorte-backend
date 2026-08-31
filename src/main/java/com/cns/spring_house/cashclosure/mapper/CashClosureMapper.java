package com.cns.spring_house.cashclosure.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureExchangeDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureInvoicingDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureRefundsDTO;
import com.cns.spring_house.cashclosure.dto.ClosureRefundDTO;
import com.cns.spring_house.cashclosure.dto.ClosureRefundMethodDTO;
import com.cns.spring_house.cashclosure.entity.CashClosureRefund;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.ClosureExchangeInvoiceDTO;
import com.cns.spring_house.cashclosure.dto.ClosurePaymentMethodDTO;
import com.cns.spring_house.cashclosure.dto.ClosureSoldItemDTO;
import com.cns.spring_house.cashclosure.entity.CashClosure;
import com.cns.spring_house.cashclosure.entity.CashClosureExchangeInvoice;
import com.cns.spring_house.cashclosure.entity.CashClosureItem;
import com.cns.spring_house.cashclosure.entity.CashClosurePaymentMethodTotal;
import com.cns.spring_house.cashclosure.entity.ExchangeInvoiceScope;
import com.cns.spring_house.cashclosure.service.PeriodCashFigures;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.sale.entity.PaymentMethod;

/**
 * Traduce entre las cifras del periodo, la entidad y las respuestas.
 *
 * No es un mapper de MapStruct: la respuesta agrupa las cifras en bloques
 * anidados —articulos, facturacion, cambiarias, cobros— mientras que la entidad
 * las tiene planas y repartidas entre la fila principal y tres colecciones
 * hijas. No hay coincidencia de nombres que generar automaticamente.
 *
 * El punto importante esta en {@link #toResponse(CashClosure)}: lee del
 * SNAPSHOT y no toca `sale` ni al calculador. Un cierre historico responde lo
 * que se confirmo ese dia, no lo que hoy diria la base de datos.
 */
@Component
public class CashClosureMapper {

    // ── Previsualizacion: cifras vivas, sin identidad ────────────

    /**
     * El documento tal y como se veria si se cerrara ahora.
     *
     * `id`, `closedAt` y `closedBy` van en null porque todavia no existe ningun
     * cierre; es justo lo que distingue una previsualizacion de un snapshot, y
     * por eso comparten tipo en vez de tener dos.
     */
    public CashClosureResponseDTO toPreview(
            PeriodCashFigures figures, CashClosure existingClosure) {
        return new CashClosureResponseDTO(
                existingClosure == null ? null : existingClosure.getId(),
                figures.businessDate(),
                existingClosure == null ? null : existingClosure.getCreatedAt(),
                existingClosure == null ? null : fullNameOf(existingClosure.getClosedBy()),
                existingClosure != null,
                figures.items(),
                figures.itemsBilledAmount(),
                figures.invoicing(),
                figures.exchange(),
                figures.collections(),
                figures.refunds(),
                netOf(figures.collections().totalCollected(),
                        figures.refunds().totalRefunded()));
    }

    /**
     * Lo que entro menos lo que salio.
     *
     * Puede ser NEGATIVO: un dia sin ventas en el que se devuelve dinero cierra
     * en negativo, y decirlo es el objetivo. Se calcula con BigDecimal y en el
     * servidor porque es una cifra contable, no una comodidad de pantalla.
     */
    private static BigDecimal netOf(BigDecimal collected, BigDecimal refunded) {
        return collected.subtract(refunded).setScale(2, RoundingMode.HALF_UP);
    }

    // ── Cifras -> entidad ────────────────────────────────────────

    /**
     * Vuelca las cifras calculadas en el snapshot que se va a persistir.
     *
     * Todo el detalle se COPIA. En el momento en que esta fila se guarda, el
     * cierre deja de depender de `sale`: renombrar un producto, cobrar una
     * cambiaria o revertir una venta ya no puede cambiar lo que el documento
     * dice.
     */
    public void applyFigures(CashClosure closure, PeriodCashFigures figures) {
        CashClosureInvoicingDTO invoicing = figures.invoicing();
        CashClosureExchangeDTO exchange = figures.exchange();
        CashClosureCollectionsDTO collections = figures.collections();

        closure.setInvoiceCount(invoicing.invoiceCount());
        closure.setInvoiceTotal(invoicing.invoiceTotal());
        closure.setExchangeInvoiceCount(invoicing.exchangeInvoiceCount());
        closure.setExchangeInvoiceTotal(invoicing.exchangeInvoiceTotal());
        closure.setTotalInvoiced(invoicing.totalInvoiced());

        closure.setExchangeCollectedCount(exchange.collectedCount());
        closure.setExchangeCollectedAmount(exchange.collectedAmount());
        closure.setPreviousExchangeCollectedAmount(exchange.previousCollectedAmount());
        closure.setIssuedExchangePendingAmount(exchange.issuedPendingAmount());

        closure.setTotalCollected(collections.totalCollected());
        closure.setItemsBilledAmount(figures.itemsBilledAmount());

        // Las tres columnas fijas de forma de pago sobreviven porque el cierre
        // MENSUAL las suma con una sola consulta sobre `cash_closure`. El
        // desglose real, el que la pantalla lee, vive en la coleccion hija.
        applyLegacyPaymentColumns(closure, collections);

        for (ClosureSoldItemDTO item : figures.items()) {
            closure.addItem(CashClosureItem.builder()
                    .productId(item.productId())
                    .itemName(item.name())
                    .itemType(item.type())
                    .quantity(item.quantity())
                    .billedAmount(item.billedAmount())
                    .build());
        }

        for (ClosureExchangeInvoiceDTO invoice : exchange.issued()) {
            closure.addExchangeInvoice(
                    toEntity(invoice, ExchangeInvoiceScope.ISSUED));
        }
        for (ClosureExchangeInvoiceDTO invoice : exchange.collected()) {
            closure.addExchangeInvoice(
                    toEntity(invoice, ExchangeInvoiceScope.COLLECTED));
        }

        for (ClosurePaymentMethodDTO method : collections.methods()) {
            closure.addPaymentMethod(CashClosurePaymentMethodTotal.builder()
                    .paymentMethod(method.method())
                    .paymentCount(method.count())
                    .amount(method.amount())
                    .build());
        }

        applyRefunds(closure, figures.refunds());
    }

    /**
     * Congela las devoluciones del periodo dentro del cierre.
     *
     * El NOMBRE de quien devolvio se copia junto al importe. Guardar solo su id
     * dejaria que renombrar a un empleado cambiara lo que el documento de aquel
     * dia dice, que es justo lo que un snapshot existe para impedir.
     */
    private void applyRefunds(CashClosure closure, CashClosureRefundsDTO refunds) {
        closure.setRefundCount(refunds.refundCount());
        closure.setTotalRefunded(refunds.totalRefunded());

        Map<PaymentMethod, ClosureRefundMethodDTO> byMethod =
                new EnumMap<>(PaymentMethod.class);
        for (ClosureRefundMethodDTO method : refunds.methods()) {
            byMethod.put(method.method(), method);
        }

        closure.setRefundCashCount(refundCountOf(byMethod.get(PaymentMethod.CASH)));
        closure.setRefundCash(refundAmountOf(byMethod.get(PaymentMethod.CASH)));
        closure.setRefundCardCount(refundCountOf(byMethod.get(PaymentMethod.CARD)));
        closure.setRefundCard(refundAmountOf(byMethod.get(PaymentMethod.CARD)));
        closure.setRefundTransferCount(
                refundCountOf(byMethod.get(PaymentMethod.TRANSFER)));
        closure.setRefundTransfer(refundAmountOf(byMethod.get(PaymentMethod.TRANSFER)));

        for (ClosureRefundDTO refund : refunds.refunds()) {
            closure.addRefund(CashClosureRefund.builder()
                    .refundId(refund.refundId())
                    .saleId(refund.saleId())
                    .documentCode(refund.documentCode())
                    .amount(refund.amount())
                    .effectiveAt(refund.effectiveAt())
                    .paymentMethod(refund.paymentMethod())
                    .performedByName(refund.performedByName())
                    .reason(refund.reason())
                    .build());
        }
    }

    private static long refundCountOf(ClosureRefundMethodDTO method) {
        return method == null ? 0L : method.count();
    }

    private static BigDecimal refundAmountOf(ClosureRefundMethodDTO method) {
        return method == null ? BigDecimal.ZERO.setScale(2) : method.amount();
    }

    private CashClosureExchangeInvoice toEntity(
            ClosureExchangeInvoiceDTO invoice, ExchangeInvoiceScope scope) {
        return CashClosureExchangeInvoice.builder()
                .scope(scope)
                .saleId(invoice.saleId())
                .documentCode(invoice.documentCode())
                .customerName(invoice.customerName())
                .issuedAt(invoice.issuedAt())
                .amount(invoice.amount())
                .collected(invoice.collected())
                .paidAt(invoice.paidAt())
                .paymentMethod(invoice.paymentMethod())
                .build();
    }

    private void applyLegacyPaymentColumns(
            CashClosure closure, CashClosureCollectionsDTO collections) {

        Map<PaymentMethod, ClosurePaymentMethodDTO> byMethod =
                new EnumMap<>(PaymentMethod.class);
        for (ClosurePaymentMethodDTO method : collections.methods()) {
            byMethod.put(method.method(), method);
        }

        closure.setCashPaymentCount(countOf(byMethod.get(PaymentMethod.CASH)));
        closure.setCashPayments(amountOf(byMethod.get(PaymentMethod.CASH)));
        closure.setCardPaymentCount(countOf(byMethod.get(PaymentMethod.CARD)));
        closure.setCardPayments(amountOf(byMethod.get(PaymentMethod.CARD)));
        closure.setTransferPaymentCount(countOf(byMethod.get(PaymentMethod.TRANSFER)));
        closure.setTransferPayments(amountOf(byMethod.get(PaymentMethod.TRANSFER)));
    }

    private static long countOf(ClosurePaymentMethodDTO method) {
        return method == null ? 0L : method.count();
    }

    private static BigDecimal amountOf(ClosurePaymentMethodDTO method) {
        return method == null ? BigDecimal.ZERO.setScale(2) : method.amount();
    }

    // ── Snapshot -> respuesta ────────────────────────────────────

    /**
     * El cierre confirmado, leido ENTERO del snapshot.
     *
     * Ni una sola cifra se recalcula. El estado de cada cambiaria es el que
     * tenia al cerrar, no el de hoy: por eso el historico del lunes sigue
     * diciendo "pendiente" aunque el documento se cobrara el miercoles.
     */
    public CashClosureResponseDTO toResponse(CashClosure closure) {
        return new CashClosureResponseDTO(
                closure.getId(),
                closure.getBusinessDate(),
                closure.getCreatedAt(),
                fullNameOf(closure.getClosedBy()),
                true,
                closure.getItems().stream()
                        .sorted(Comparator
                                .comparing(CashClosureItem::getBilledAmount).reversed()
                                .thenComparing(CashClosureItem::getItemName)
                                .thenComparing(CashClosureItem::getProductId))
                        .map(CashClosureMapper::toItemResponse)
                        .toList(),
                closure.getItemsBilledAmount(),
                new CashClosureInvoicingDTO(
                        closure.getInvoiceCount(),
                        closure.getInvoiceTotal(),
                        closure.getExchangeInvoiceCount(),
                        closure.getExchangeInvoiceTotal(),
                        closure.getTotalInvoiced()),
                toExchangeResponse(closure),
                toCollectionsResponse(closure),
                toRefundsResponse(closure),
                netOf(closure.getTotalCollected(), closure.getTotalRefunded()));
    }

    /** Las devoluciones del snapshot, leidas y NUNCA recalculadas. */
    private CashClosureRefundsDTO toRefundsResponse(CashClosure closure) {
        List<ClosureRefundMethodDTO> methods = List.of(
                new ClosureRefundMethodDTO(PaymentMethod.CASH,
                        closure.getRefundCashCount(), closure.getRefundCash()),
                new ClosureRefundMethodDTO(PaymentMethod.CARD,
                        closure.getRefundCardCount(), closure.getRefundCard()),
                new ClosureRefundMethodDTO(PaymentMethod.TRANSFER,
                        closure.getRefundTransferCount(), closure.getRefundTransfer()));

        List<ClosureRefundDTO> refunds = new ArrayList<>();
        closure.getRefunds().stream()
                .sorted(Comparator
                        .comparing(CashClosureRefund::getEffectiveAt)
                        .thenComparing(CashClosureRefund::getRefundId))
                .forEach(refund -> refunds.add(new ClosureRefundDTO(
                        refund.getRefundId(),
                        refund.getSaleId(),
                        refund.getDocumentCode(),
                        refund.getAmount(),
                        refund.getEffectiveAt(),
                        refund.getPaymentMethod(),
                        refund.getPerformedByName(),
                        refund.getReason())));

        return new CashClosureRefundsDTO(
                methods,
                closure.getRefundCount(),
                closure.getTotalRefunded(),
                List.copyOf(refunds));
    }

    private static ClosureSoldItemDTO toItemResponse(CashClosureItem item) {
        return new ClosureSoldItemDTO(
                item.getProductId(),
                item.getItemName(),
                item.getItemType(),
                item.getQuantity(),
                item.getBilledAmount());
    }

    private CashClosureExchangeDTO toExchangeResponse(CashClosure closure) {
        List<ClosureExchangeInvoiceDTO> issued = scopedInvoices(
                closure, ExchangeInvoiceScope.ISSUED);
        List<ClosureExchangeInvoiceDTO> collected = scopedInvoices(
                closure, ExchangeInvoiceScope.COLLECTED);

        return new CashClosureExchangeDTO(
                closure.getExchangeInvoiceCount(),
                closure.getExchangeInvoiceTotal(),
                closure.getIssuedExchangePendingAmount(),
                closure.getExchangeCollectedCount(),
                closure.getExchangeCollectedAmount(),
                closure.getPreviousExchangeCollectedAmount(),
                issued,
                collected);
    }

    private List<ClosureExchangeInvoiceDTO> scopedInvoices(
            CashClosure closure, ExchangeInvoiceScope scope) {

        List<ClosureExchangeInvoiceDTO> invoices = new ArrayList<>();
        closure.getExchangeInvoices().stream()
                .filter(invoice -> invoice.getScope() == scope)
                .sorted(Comparator
                        .comparing(CashClosureExchangeInvoice::getIssuedAt)
                        .thenComparing(CashClosureExchangeInvoice::getSaleId))
                .forEach(invoice -> invoices.add(new ClosureExchangeInvoiceDTO(
                        invoice.getSaleId(),
                        invoice.getDocumentCode(),
                        invoice.getCustomerName(),
                        invoice.getIssuedAt(),
                        invoice.getAmount(),
                        invoice.isCollected(),
                        invoice.getPaidAt(),
                        invoice.getPaymentMethod())));
        return List.copyOf(invoices);
    }

    /**
     * El desglose por forma de pago del snapshot, ordenado por el enum para que
     * la pantalla lo pinte siempre igual, y completado con las formas que no
     * tengan fila: un cierre antiguo pudo guardarse antes de que existiera un
     * metodo nuevo, y omitirlo dejaria la tabla incompleta.
     */
    private CashClosureCollectionsDTO toCollectionsResponse(CashClosure closure) {
        Map<PaymentMethod, CashClosurePaymentMethodTotal> byMethod =
                new EnumMap<>(PaymentMethod.class);
        for (CashClosurePaymentMethodTotal total : closure.getPaymentMethods()) {
            byMethod.put(total.getPaymentMethod(), total);
        }

        List<ClosurePaymentMethodDTO> methods = new ArrayList<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            CashClosurePaymentMethodTotal total = byMethod.get(method);
            methods.add(new ClosurePaymentMethodDTO(
                    method,
                    total == null ? 0L : total.getPaymentCount(),
                    total == null ? BigDecimal.ZERO.setScale(2) : total.getAmount()));
        }

        return new CashClosureCollectionsDTO(
                List.copyOf(methods), closure.getTotalCollected());
    }

    private static String fullNameOf(Employee employee) {
        if (employee == null) {
            return null;
        }
        return (employee.getFirstName() + " " + employee.getLastName()).trim();
    }

}

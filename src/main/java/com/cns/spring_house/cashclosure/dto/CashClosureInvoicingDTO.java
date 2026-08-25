package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;

/**
 * Lo FACTURADO: documentos emitidos, contados por {@code completedAt}.
 *
 * Una factura cambiaria emitida y no cobrada suma aqui con su importe integro,
 * porque el documento existe desde que se emite. Lo que se haya cobrado de ella
 * es otra dimension y vive en {@link CashClosureCollectionsDTO}.
 */
public record CashClosureInvoicingDTO(

    long invoiceCount,
    BigDecimal invoiceTotal,
    long exchangeInvoiceCount,
    BigDecimal exchangeInvoiceTotal,
    /** {@code invoiceTotal + exchangeInvoiceTotal}. */
    BigDecimal totalInvoiced

) {

}

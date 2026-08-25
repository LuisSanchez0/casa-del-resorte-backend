package com.cns.spring_house.cashclosure.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Respuesta de {@code GET /v1/cash-closures/preview}: las cifras del dia tal y
 * como estan AHORA, sin arqueo, porque todavia no se ha declarado el fondo
 * inicial ni el efectivo contado.
 *
 * Consultarla no cierra, no persiste y no bloquea ventas.
 *
 * {@code alreadyClosed} existe para que la pantalla sepa que la caja del dia ya
 * se cerro y ofrezca el snapshot en lugar de un segundo cierre; en ese caso
 * {@code closureId} apunta al documento existente. Las cifras que acompanan
 * siguen siendo las dinamicas: son informativas, y el historico oficial es el
 * que responde {@code GET /v1/cash-closures/{id}}.
 */
public record CashClosureBasePreviewResponseDTO(

    LocalDate businessDate,
    CashClosureInvoicingDTO invoicing,
    CashClosureCollectionsDTO collections,
    CashClosureReceivablesDTO receivables,
    boolean alreadyClosed,
    /** Null mientras la caja del dia siga abierta. */
    UUID closureId

) {

}

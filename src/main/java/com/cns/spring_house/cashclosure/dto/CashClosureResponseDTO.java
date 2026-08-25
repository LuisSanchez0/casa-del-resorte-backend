package com.cns.spring_house.cashclosure.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un cierre de caja completo, con arqueo.
 *
 * La MISMA forma sirve para la previsualizacion final y para el snapshot
 * confirmado, porque el sentido de la previsualizacion es ensenar exactamente
 * el documento que se va a persistir. Se distinguen por sus campos, no por su
 * tipo:
 *
 * <pre>
 * previsualizacion -> id = null, closedAt = null, closedBy = null
 * cierre confirmado -> los tres con valor
 * </pre>
 *
 * {@code closedAt} no tiene columna propia: es el {@code createdAt} de
 * auditoria de la fila, que se escribe al insertarla y nunca se modifica.
 */
public record CashClosureResponseDTO(

    /** Null en previsualizacion: todavia no existe documento. */
    UUID id,
    LocalDate businessDate,
    /** Instante en que se confirmo el cierre. Null en previsualizacion. */
    LocalDateTime closedAt,
    /** Nombre del responsable del cierre. Null en previsualizacion. */
    String closedBy,
    /**
     * En previsualizacion, si el dia YA tenia un cierre confirmado; en un
     * snapshot es siempre true.
     */
    boolean alreadyClosed,
    CashClosureInvoicingDTO invoicing,
    CashClosureCollectionsDTO collections,
    CashClosureReceivablesDTO receivables,
    CashReconciliationDTO cashReconciliation,
    String notes

) {

}

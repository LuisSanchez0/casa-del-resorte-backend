package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Un cierre de caja diario completo.
 *
 * La MISMA forma sirve para la previsualizacion en vivo y para el snapshot
 * confirmado, porque el sentido de la previsualizacion es ensenar exactamente el
 * documento que se va a persistir. Se distinguen por sus campos, no por su tipo:
 *
 * <pre>
 * previsualizacion  -> id = null, closedAt = null, closedBy = null
 * cierre confirmado -> los tres con valor
 * </pre>
 *
 * El orden de los apartados es el de las preguntas que el cierre responde:
 *
 * <pre>
 * 1. items        que se vendio
 * 2. invoicing    cuanto se emitio, y de que tipo
 * 3. exchange     que quedo a credito y que se cobro de credito
 * 4. collections  como entro el dinero
 *    totalCollected  cuanto entro de verdad
 * </pre>
 *
 * NO hay arqueo. El fondo inicial, el efectivo contado y la diferencia
 * desaparecieron del modelo: nadie cuenta el cajon contra un fondo declarado, y
 * mantener los campos habria obligado a seguir pidiendolos para nada.
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

    List<ClosureSoldItemDTO> items,
    /** Suma de {@code items.billedAmount}. */
    BigDecimal itemsBilledAmount,

    CashClosureInvoicingDTO invoicing,
    CashClosureExchangeDTO exchange,
    CashClosureCollectionsDTO collections,

    /**
     * El dinero que SALIO en el periodo. Nunca restado de `collections`: son
     * dos hechos distintos y fundirlos daria un numero que no responde ninguna
     * de las dos preguntas del taller.
     */
    CashClosureRefundsDTO refunds,

    /**
     * `totalCollected - totalRefunded`.
     *
     * Se calcula en el servidor y no en la pantalla: es una cifra contable, y
     * una resta hecha en el navegador no es fuente autorizada de nada. Puede
     * ser NEGATIVO —un dia sin ventas en el que se devuelve dinero— y eso es
     * informacion, no un error.
     */
    BigDecimal netPeriod

) { }

package com.cns.spring_house.cashclosure.service;

import java.time.LocalDate;
import java.util.UUID;

import com.cns.spring_house.cashclosure.dto.CashClosureDocumentDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureMonthlyResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;

/**
 * Cierre de caja diario y mensual.
 *
 * El taller nunca es un parametro: sale del token en cada operacion, incluida la
 * exportacion.
 *
 * Hay una frontera dura entre {@link #getPreview()} y el resto: la
 * previsualizacion calcula contra `sale` y NO persiste ni bloquea nada; {@link
 * #close()} persiste el snapshot, y a partir de ahi {@link #get}, {@link
 * #getByDate}, {@link #getMonthly} y el PDF historico leen SOLO ese snapshot.
 *
 * Ya no hay arqueo. Ninguna de estas operaciones acepta fondo inicial, efectivo
 * contado ni observaciones: el cierre describe lo que se facturo y lo que se
 * cobro, no un recuento manual del cajon.
 */
public interface CashClosureService {

    /**
     * El dia en curso tal y como esta AHORA: articulos vendidos, documentos
     * emitidos, cambiarias y cobros. No persiste nada y no bloquea ventas.
     *
     * Cuando el dia ya fue cerrado responde igual, con {@code alreadyClosed} en
     * true y el id del cierre, para que la pantalla ofrezca el snapshot.
     */
    CashClosureResponseDTO getPreview();

    /**
     * Confirma el cierre del dia y persiste el snapshot.
     *
     * No recibe nada: TODO lo que compone el cierre lo calcula el servidor
     * contra la base de datos. Aceptar cualquier cifra del cliente permitiria
     * cerrar una caja con los numeros que uno quisiera.
     */
    CashClosureResponseDTO close();

    CashClosureResponseDTO get(UUID id);

    CashClosureResponseDTO getByDate(LocalDate date);

    /**
     * Cierre MENSUAL: la misma estructura contable que un dia, agregada sobre
     * los cierres diarios confirmados del mes.
     */
    CashClosureMonthlyResponseDTO getMonthly(int year, int month);

    /** PDF de un cierre confirmado, generado desde el snapshot. */
    CashClosureDocumentDTO document(UUID id);

}
